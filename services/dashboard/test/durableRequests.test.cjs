const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs'),path=require('node:path'),Module=require('node:module'),ts=require('typescript');
const filename=path.resolve(__dirname,'../src/lib/durableRequests.ts');
const moduleCopy=new Module(filename);
moduleCopy._compile(ts.transpileModule(fs.readFileSync(filename,'utf8'),{compilerOptions:{module:ts.ModuleKind.CommonJS,target:ts.ScriptTarget.ES2022}}).outputText,filename);
const {DurableRequests}=moduleCopy.exports;
function storage(){const data=new Map();return{getItem:k=>data.get(k)??null,setItem:(k,v)=>data.set(k,v)}}
test('응답 유실 후 요청 키를 복원하고 명령 payload는 저장하지 않는다',()=>{
 const disk=storage(),before=new DurableRequests(disk,'robot');
 assert.equal(before.add('request','key'),true);
 const after=new DurableRequests(disk,'robot');
 assert.deepEqual(after.all(),[{requestId:'request',idempotencyKey:'key'}]);
 after.finish('request');assert.deepEqual(new DurableRequests(disk,'robot').all(),[]);
});
test('브라우저 저장 실패와 100개 한도에서 전송을 허용하지 않는다',()=>{
 const broken=new DurableRequests({getItem:()=>null,setItem:()=>{throw Error('full')}},'robot');
 assert.equal(broken.add('r','k'),false);assert.deepEqual(broken.all(),[]);
 const state=new DurableRequests(storage(),'robot');for(let i=0;i<100;i++)assert.equal(state.add('r'+i,'k'+i),true);
 assert.equal(state.add('overflow','key'),false);
});
