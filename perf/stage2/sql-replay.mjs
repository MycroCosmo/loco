import assert from 'node:assert/strict';
import {sqlString} from './data.mjs';

const tokens=sql=>sql.match(/"(?:[^"]|"")*"|'(?:[^']|'')*'|\$\d+|::|:[a-zA-Z_]\w*|[a-zA-Z_]\w*|\d+(?:\.\d+)?|>=|<=|<>|!=|\S/g)||[];

// Replay only the SQL shape actually observed. Literal positions come from the source,
// not guessed pg_stat_statements parameter numbers; all request values are explicit.
export function bindTemplate(observed,template,parameters) {
  const source=tokens(template),actual=tokens(observed),bindings=new Map();
  assert.equal(actual.length,source.length,'Observed SQL shape differs from the source');
  return actual.map((token,i)=>{
    const original=source[i];
    if(/^\$\d+$/.test(token)) {
      let value;
      if(/^:[a-zA-Z_]/.test(original)) {
        const name=original.slice(1);
        assert.ok(Object.hasOwn(parameters,name),'Missing request parameter '+name);
        value=sqlString(parameters[name]);
      } else {
        assert.match(original,/^(?:\d+(?:\.\d+)?|null|true|false|'(?:[^']|'')*')$/i,'Unexpected normalized literal');
        value=original;
      }
      if(bindings.has(token))assert.equal(bindings.get(token),value,'Conflicting observed binding');
      bindings.set(token,value);return value;
    }
    assert.equal(token.toLowerCase(),original.toLowerCase(),'Observed SQL token differs from the source');
    return token;
  }).join(' ');
}

export function javaSqlTemplate(source,name) {
  assert.match(name,/^[A-Z_]+$/);
  const match=source.match(new RegExp('String '+name+'\\s*=\\s*"""([\\s\\S]*?)""";'));
  assert.ok(match,'SQL template not found: '+name);return match[1].trim();
}
