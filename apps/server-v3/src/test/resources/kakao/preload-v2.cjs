// Test-only network substitution, separate from behavior-preserving timing instrumentation.
if (!process.argv[1]?.endsWith('start-v2.cjs')) return;
delete process.env.KAKAO_REST_API_KEY;
const http = require('node:http');
const nativeFetch = global.fetch;
const port = 39091;
let settings = {variant: 'normal'}, requests = [], rewrites = 0, blocked = 0;
const normal = {
  meta: {total_count: 2, is_end: false, pageable_count: 45},
  documents: [{id: 'p1', place_name: 'Cafe', address_name: 'address', road_address_name: 'road',
    phone: '010', category_name: 'food', x: '127.25', y: '37.5', place_url: 'https://example.invalid/p1'},
    {x: 'bad', y: '1.25x'}]
};

http.createServer((req, res) => {
  const url = new URL(req.url, 'http://localhost');
  if (url.pathname === '/__control') {
    if (req.method === 'POST') {
      let input = '';
      req.on('data', part => input += part);
      req.on('end', () => {
        settings = JSON.parse(input); requests = []; rewrites = 0; blocked = 0;
        res.end('{}');
      });
    } else {
      res.setHeader('Content-Type', 'application/json');
      res.end(JSON.stringify({requests, rewrites, blocked}));
    }
    return;
  }
  if (url.pathname !== '/v2/local/search/keyword.json') {res.writeHead(404); res.end(); return;}
  requests.push({path: url.pathname, query: Object.fromEntries(url.searchParams),
    authorizationPresent: !!req.headers.authorization});
  const variant = settings.variant;
  if (variant === 'connection-failure') {req.socket.destroy(); return;}
  if (['400','401','429','500','503'].includes(variant)) {
    res.writeHead(Number(variant), {'Content-Type': 'text/plain'});
    res.end(settings.body ?? ('fake upstream ' + variant + '\nbody detail')); return;
  }
  if (variant === 'error-body-drop') {
    res.writeHead(503, {'Content-Length': '1000'}); res.flushHeaders(); res.write('partial');
    setTimeout(() => res.destroy(), 30); return;
  }
  if (variant === 'invalid-json') {res.end('{broken'); return;}
  if (variant === '204') {res.writeHead(204); res.end(); return;}
  const body = variant === 'missing-meta' ? {documents: []}
    : variant === 'missing-documents' ? {meta: {}}
    : variant === 'empty-meta' ? {meta: {}, documents: []}
    : variant === 'wrong-types' ? {meta: {total_count: 'two', is_end: 'yes'}, documents: [{id: 7, place_name: false, x: null, y: 'Infinity'}]}
    : normal;
  res.setHeader('Content-Type', 'application/json');
  const send = () => res.end(JSON.stringify(body));
  if (variant === 'slow-body') {res.flushHeaders(); res.write(' ');}
  if (['delay', 'slow-body'].includes(variant)) setTimeout(send, settings.delayMillis ?? 250);
  else send();
}).listen(port, '0.0.0.0');

global.fetch = async (url, options = {}) => {
  const target = new URL(typeof url === 'string' ? url : url.url);
  if (target.origin !== 'https://dapi.kakao.com' || target.pathname !== '/v2/local/search/keyword.json') {
    blocked++;
    throw new Error('Non-fixture fetch blocked');
  }
  rewrites++;
  // Never forward the original Authorization, use an API key, or follow an external redirect.
  return nativeFetch('http://127.0.0.1:' + port + target.pathname + target.search,
    {method: 'GET', redirect: 'error'});
};
