import os
from pathlib import Path
import subprocess
import sys
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'scalability'))
from lab_lock import lock

if __name__=='__main__':
    with lock(Path(__file__).resolve().parents[2]/'tmp/failover/.lab.lock'):
        raise SystemExit(subprocess.run(sys.argv[1:],env={**os.environ,'ROKIN_FAILOVER_LOCK_HELD':'1'}).returncode)
