"""활성/대기 ROS 컨테이너의 인계와 이전 실행 차단을 검증한다. 볼륨은 보존한다."""
import argparse
import json
from pathlib import Path
import subprocess
import sys
import time
import urllib.error
import uuid

ROOT=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT/'scripts/scalability'))
from lab_lock import lock
from measure import Client,request

PREFIX='rokin-runtime-lab-'
APIS=['http://localhost:18491','http://localhost:18492']
RUNTIMES=[PREFIX+'fake_robot-1',PREFIX+'fake_robot-b-1']
PROXIES={RUNTIMES[0]:PREFIX+'runtime-api-a-1',RUNTIMES[1]:PREFIX+'runtime-api-b-1'}


def docker(*args):
    return subprocess.check_output(['docker',*args],text=True,stderr=subprocess.STDOUT,timeout=45).strip()


def sql(query):
    return docker('exec',PREFIX+'postgres-1','psql','-v','ON_ERROR_STOP=1','-U','postgres','-d','telemetry','-Atc',query)


def wait(check,seconds=45):
    deadline=time.monotonic()+seconds
    while time.monotonic()<deadline:
        try:
            value=check()
            if value:return value
        except (urllib.error.URLError,ConnectionError,TimeoutError,KeyError):pass
        time.sleep(.2)
    raise AssertionError('runtime failover did not converge')


def robot(api=0):
    return next(r for r in request(APIS[api]+'/api/robots') if r['robotId']=='robot_1')


def ready(previous=None):
    a,b=robot(),robot(1)
    configs=request(APIS[0]+'/api/simulations')
    running=next(r for r in configs if r['robotId']=='robot_1')['state']=='RUNNING'
    return a if (a['online'] and b['online'] and running and a['runtimeId']==b['runtimeId'] and
                 a['runtimeId'] and a['runtimeId']!=previous and a.get('pose') is not None) else None


def owner(runtime):
    for container in RUNTIMES:
        logs=docker('logs',container,'--tail','300')
        if f'Runtime lease acquired runtime={runtime} ' in logs:return container
    raise AssertionError('owner container not found')


def send(client,action='move_to',data=None):
    correlation=str(uuid.uuid4())
    client.send('SEND',{'destination':'/app/command','content-type':'application/json'},json.dumps(dict(
        requestId=correlation,idempotencyKey=str(uuid.uuid4()),robotId='robot_1',command=action,data=data or {})))
    result=wait(lambda:client.results.get(correlation)) [0]
    assert result['status']=='accepted',result
    return result['cmdId']


def replay(container,payload):
    code="from confluent_kafka import Producer; import sys; p=Producer({'bootstrap.servers':'kafka:29092','acks':'all'}); errors=[]; p.produce('cmd.robot',key='robot_1',value=sys.argv[1],on_delivery=lambda e,m:errors.append(e)); left=p.flush(10); assert left==0 and errors==[None],(left,errors)"
    docker('exec',container,'python3','-c',code,payload)


def run(output):
    result={'scenarios':[]}
    clients=[]
    resumed=[]
    disconnected=[]
    try:
        for api in APIS:
            client=Client(0,api,['robot_1']);client.start();clients.append(client)
            wait(lambda:client.ws_ready.is_set(),30)
        for fault in ['kill','api-partition','suspend']:
            current=wait(ready);old=current['runtimeId'];active=owner(old)
            other=next(c for c in RUNTIMES if c!=active)
            print(f'fault={fault} active={active}',flush=True)
            command=send(clients[0],data={'x':1000,'y':0})
            wait(lambda:any(ack['status']=='accepted' for ack,_ in clients[0].acks.get(command,[])))
            wait(lambda:robot()['pose']['x']>1)
            payload=sql("SELECT payload FROM command_outbox WHERE cmd_id='"+command+"'")
            assert payload,'enable COMMAND_DURABLE_ENABLED in the isolated lab'
            started=time.monotonic()
            if fault=='kill':docker('kill','--signal','SIGKILL',active)
            elif fault=='api-partition':
                docker('stop',PROXIES[active]);disconnected.append(PROXIES[active])
            else:
                docker('pause',active);resumed.append(active)
            new=wait(lambda:ready(old),45)
            elapsed=time.monotonic()-started
            assert owner(new['runtimeId'])==other,'standby did not acquire ownership'
            assert new['state']=='idle' and abs(new['pose']['x'])<.01,'old movement resumed'
            assert new['battery']['level']>98,'battery did not reset'
            if fault=='suspend':
                docker('unpause',active);resumed.remove(active)
            wait(lambda:json.loads(docker('inspect',active))[0]['State']['Running'] is False,25)
            if fault=='api-partition':
                docker('start',PROXIES[active]);disconnected.remove(PROXIES[active])
            docker('start',active)
            wait(lambda:request(APIS[1]+'/api/commands/'+command)['deliveryStatus']=='OUTCOME_UNKNOWN',40)
            # 이전 서버의 디스크 결과는 대기 상태에서도 회수한다.
            time.sleep(3)
            assert ready()['runtimeId']==new['runtimeId'],'restarted old server preempted lease'
            replay(other,payload)
            time.sleep(2)
            stable=robot()
            assert stable['runtimeId']==new['runtimeId'] and abs(stable['pose']['x'])<.01,'stale command affected new robot'
            stop=send(clients[1],'stop')
            wait(lambda:request(APIS[0]+'/api/commands/'+stop)['deliveryStatus']=='COMPLETED')
            # API 프로세스 재시작은 DB lease 소유자를 바꾸지 않는다.
            if fault=='kill':
                docker('restart','--time','5',PREFIX+'api-b-1')
                wait(lambda:request(APIS[1]+'/actuator/health')['status']=='UP',45)
                assert wait(ready)['runtimeId']==new['runtimeId']
                clients[1].stop.set()
                clients[1]=Client(1,APIS[1],['robot_1']);clients[1].start()
                wait(lambda:clients[1].ws_ready.is_set(),30)
            result['scenarios'].append(dict(fault=fault,oldRuntime=old,newRuntime=new['runtimeId'],
                                           failoverSeconds=elapsed,command=command,oldOutcome='OUTCOME_UNKNOWN',
                                           staleCommandBlocked=True))
            print(result['scenarios'][-1],flush=True)
        result['passed']=True
    finally:
        for container in resumed:
            subprocess.run(['docker','unpause',container],capture_output=True,timeout=10)
        for container in disconnected:
            subprocess.run(['docker','start',container],capture_output=True,timeout=20)
        for client in clients:client.stop.set()
        output.parent.mkdir(parents=True,exist_ok=True)
        output.write_text(json.dumps(result,indent=2)+'\n')
    return result


if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--output',type=Path,required=True);args=parser.parse_args()
    with lock(ROOT/'tmp/failover/.lab.lock'):
        print(json.dumps(run(args.output)),flush=True)
