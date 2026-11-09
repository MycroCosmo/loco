import fs from 'node:fs';
import path from 'node:path';
import {spawnSync} from 'node:child_process';
import {gunzipSync} from 'node:zlib';
import {hash,stageSettings} from './data.mjs';
import assert from 'node:assert/strict';

const {output:base,project,stage}=stageSettings();
const destination=process.argv[2];
assert.match(destination||'',/^p[012]-[a-z0-9-]+$/);
assert.ok(destination.startsWith(stage+'-'),'Bundle name must match PERF_STAGE');
const staging=`apps/server-v3/build/${destination}`;
assert.ok(!fs.existsSync(staging),'Use a new bundle name; never overwrite prior evidence');
fs.mkdirSync(staging,{recursive:true});
const secrets=Object.values(JSON.parse(fs.readFileSync(path.join(base,'private/secrets.json'))));
const credentials=JSON.parse(fs.readFileSync(path.join(base,'private/credentials.json')));
secrets.push(credentials.jwt,...credentials.cookie.split(';').map(s=>s.trim().split('=').slice(1).join('=')).filter(s=>s.length>12));
const sums=[];
function visit(relative='') {
  for(const entry of fs.readdirSync(path.join(base,relative),{withFileTypes:true})) {
    const target=path.join(relative,entry.name);
    if(entry.name==='private'||entry.name==='preflight'||entry.name.endsWith('.jfr'))continue;
    if(entry.isDirectory()){visit(target);continue;}
    if(!/\.(json|jsonl|gz|log|md|txt)$/.test(entry.name))continue;
    let buffer=fs.readFileSync(path.join(base,target));
    if(entry.name==='resources.jsonl') {
      buffer=Buffer.from(buffer.toString('utf8').trim().split('\n').filter(Boolean).map(line=>{
        const sample=JSON.parse(line);
        for(const item of sample.stats||[])if(!item.name.startsWith('/'+project)&&!item.name.startsWith('background-'))item.name='background-'+hash(item.name).slice(0,8);
        return JSON.stringify(sample);
      }).join('\n')+'\n');
    } else if(entry.name.endsWith('.json')) {
      const content=JSON.parse(buffer);
      if(content.environment?.background)content.environment.background=content.environment.background.map(item=>({alias:item.alias||'background-'+hash(item.id||item.name).slice(0,8)}));
      buffer=Buffer.from(JSON.stringify(content,null,2));
    }
    const clear=entry.name.endsWith('.gz')?gunzipSync(buffer):buffer;
    for(const secret of secrets)if(secret)assert.ok(!clear.includes(Buffer.from(secret)),`Secret detected: ${target}; package stopped`);
    assert.ok(!/eyJhbGciOi|(?:JSESSIONID|XSRF-TOKEN)=[A-Za-z0-9%-]{12,}/.test(clear.toString('utf8')),`Authentication material detected: ${target}`);
    const file=path.join(staging,target);fs.mkdirSync(path.dirname(file),{recursive:true});fs.writeFileSync(file,buffer);
    sums.push(hash(buffer)+'  '+target.replaceAll('\\','/'));
  }
}
visit();
fs.writeFileSync(path.join(staging,'SHA256SUMS.txt'),sums.sort().join('\n')+'\n');
fs.writeFileSync(path.join(staging,'REDACTION.txt'),'Private credentials, preflight response cache and binary JFR omitted. Other-project container names are pseudonymized; their resource measurements are unchanged. JFR event exports omit environment/system properties. Failed/preparation runs remain marked.\n');
const archive=staging+'.tar.gz';
const result=spawnSync('tar',['-czf',path.resolve(archive),'-C',path.resolve(staging),'.'],{encoding:'utf8',windowsHide:true});
assert.equal(result.status,0,result.stderr);
const checksum=hash(fs.readFileSync(archive));
fs.writeFileSync(archive+'.sha256',checksum+'  '+path.basename(archive)+'\n');
console.log(JSON.stringify({archive,sha256:checksum,bytes:fs.statSync(archive).size,files:sums.length}));
