import fs from 'node:fs';
import path from 'node:path';
import assert from 'node:assert/strict';
import {spawnSync} from 'node:child_process';
import {hash} from './data.mjs';

const [tag,codeSha,resultSha,archive,report]=process.argv.slice(2);
assert.match(tag||'',/^perf-stage2-p[012]-[a-z0-9-]+$/);
const stage=tag.split('-')[2].toUpperCase();
assert.ok(!report||['E14_E15_RESULTS.md','P2_WRITE_RETEST.md'].includes(report),'Only an approved paired report may override the default');
const reportFile=report||`${stage}_RESULTS.md`;
const description=report==='P2_WRITE_RETEST.md'?'P2 단일 요청자 쓰기 A1/B 전체5쌍 새 세션. 예열10+본30 CRUD·AB/BA·간섭·편차 기준 불변, 기존 원본과 분리. 모든 결과를 보존하며 최종 판정은 결과 문서를 따릅니다.':report?'E-14 ROUND 표현식 인덱스·쓰기 비용, E-15 원본 P0와 P1+E-14의 같은 세션 AB/BA 5쌍. 1% 50req/s·10% 16req/s. 실패도 보존하며 판정은 결과 문서를 따릅니다.':stage==='P2' ? '원래 P0 대비1%50req/s 회귀와 A1 대비10%16req/s 개선의 같은 세션 AB/BA5쌍,전체 viewport·쓰기 동시1/2/4/8/16.무효·실패 회차도 보존하며 채택은 결과 문서를 따릅니다.' : stage==='P0'
  ? 'M·편향·hot80%·viewport1%, A5회. 탐색은 채택 근거 제외. E-10 지연 budget 미확정.'
  : '좌표 정합성·결정적 overflow 반영 후 M·편향·hot80%·viewport10% 새 기준선과 viewport1% 회귀, 전체 범위 스트레스. p95 200ms·오류0·dropped0. 실패/상한·반복 결과는 결과 문서를 따릅니다.';
assert.match(codeSha||'',/^[0-9a-f]{40}$/);assert.match(resultSha||'',/^[0-9a-f]{40}$/);
assert.ok(path.resolve(archive).startsWith(path.resolve('apps/server-v3/build')+path.sep));
const credential=spawnSync('git',['credential','fill'],{input:'protocol=https\nhost=github.com\n\n',encoding:'utf8',windowsHide:true});
assert.equal(credential.status,0,'GitHub credential helper unavailable');
const token=credential.stdout.split('\n').find(s=>s.startsWith('password='))?.slice(9);
assert.ok(token,'GitHub token unavailable');
const headers={Authorization:'Bearer '+token,Accept:'application/vnd.github+json','X-GitHub-Api-Version':'2022-11-28'};
const api='https://api.github.com/repos/MycroCosmo/loco';
let response=await fetch(`${api}/releases/tags/${tag}`,{headers});
let release;
if(response.status===404) {
  response=await fetch(`${api}/releases`,{method:'POST',headers:{...headers,'Content-Type':'application/json'},body:JSON.stringify({tag_name:tag,target_commitish:codeSha,name:stage+' 로컬 기준선 — '+tag,draft:false,prerelease:true,body:`운영 배포가 아닌 로컬 ${stage} 측정 원본입니다.\n\n측정 코드: ${codeSha}\n결과 문서: https://github.com/MycroCosmo/loco/blob/${resultSha}/perf/stage2/${reportFile}\n\n${description} 노트북 상대 비교이며 운영 절대 수치가 아닙니다. private 자격증명 제외·배경 컨테이너 비식별 처리·SHA256SUMS 포함.`})});
}
assert.ok(response.ok,`Release API failed ${response.status}`);release=await response.json();
assert.equal(release.target_commitish,codeSha,'Release target differs; never overwrite');
for(const file of [archive,archive+'.sha256']) {
  const name=path.basename(file),bytes=fs.readFileSync(file),sha=hash(bytes);
  const existing=release.assets.find(a=>a.name===name);
  if(existing) {assert.equal(existing.digest,'sha256:'+sha);continue;}
  const url=release.upload_url.split('{')[0]+'?name='+encodeURIComponent(name);
  const uploaded=await fetch(url,{method:'POST',headers:{...headers,'Content-Type':'application/octet-stream','Content-Length':String(bytes.length)},body:bytes});
  assert.ok(uploaded.ok,`Asset upload failed ${uploaded.status}`);
  const asset=await uploaded.json();assert.equal(asset.size,bytes.length);assert.equal(asset.digest,'sha256:'+sha);
  console.log(JSON.stringify({asset:asset.browser_download_url,bytes:asset.size,sha256:sha}));
}
console.log(JSON.stringify({release:release.html_url,codeSha,resultSha}));
