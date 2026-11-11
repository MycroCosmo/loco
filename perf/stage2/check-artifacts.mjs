import {stageSettings} from './data.mjs';
const output=process.argv[2]||stageSettings().output;
import {spawnSync} from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import assert from 'node:assert/strict';
const directory='apps/server-v3/build/libs';
const java=path.join(process.env.JAVA_HOME,'bin/java.exe'),jar=path.join(process.env.JAVA_HOME,'bin/jar.exe');
const normal=path.join(directory,'loco-server-v3-0.0.1-SNAPSHOT.jar');
const perf=path.join(directory,'loco-server-v3-0.0.1-SNAPSHOT-perf-local-only.jar');
const results=[];
for(const [file,profile] of [[normal,'perf'],[perf,'production'],[perf,'perf,production']]) {
  const r=spawnSync(java,['-jar',file],{encoding:'utf8',timeout:20000,windowsHide:true,env:{...process.env,SPRING_PROFILES_ACTIVE:profile,DB_URL:'jdbc:postgresql://unreachable.invalid:5432/must_not_connect',DB_USERNAME:'none',DB_PASSWORD:'',JWT_SECRET:''}});
  const logs=(r.stdout||'')+(r.stderr||'');
  assert.notEqual(r.status,0);assert.match(logs,/Local perf artifact requires/);
  assert.doesNotMatch(logs,/HikariPool.*Starting|Migrating schema/);
  results.push({artifact:path.basename(file),profile,rejectedBeforeDatabase:true,status:r.status});
}
const normalFiles=spawnSync(jar,['tf',normal],{encoding:'utf8',windowsHide:true});
const perfFiles=spawnSync(jar,['tf',perf],{encoding:'utf8',windowsHide:true});
assert.equal(normalFiles.status,0);assert.equal(perfFiles.status,0);
assert.doesNotMatch(normalFiles.stdout,/loco-perf-local-only|PerfReadPlans|application-perf.yml/);
assert.match(perfFiles.stdout,/loco-perf-local-only/);assert.match(perfFiles.stdout,/PerfReadPlans/);
fs.mkdirSync(output,{recursive:true});
fs.writeFileSync(path.join(output,'artifact-checks.json'),JSON.stringify({checks:4,results,normalJarExcludesPerf:true},null,2));
console.log('Packaged artifact checks: 4 passed; rejected before datasource/Flyway');
