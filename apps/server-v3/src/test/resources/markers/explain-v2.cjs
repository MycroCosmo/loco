// Read-only calls to the unmodified compiled v2 service. Query events observe only.
const {PrismaClient}=require('@prisma/client');
const {PlacesService}=require('./dist/places/places.service');
const {MapPermissionsService}=require('./dist/maps/map-permissions.service');
const input=JSON.parse(process.argv[1]);
const url=new URL(process.env.DATABASE_URL);
url.username=process.env.DB_USERNAME;url.password=process.env.DB_PASSWORD;
process.env.DATABASE_URL=url.toString();
const db=new PrismaClient({log:[{emit:'event',level:'query'}]});
let events=[];
db.$on('query',e=>events.push({sql:e.query,params:JSON.parse(e.params)}));
(async()=>{
  const service=new PlacesService(db,null,null,new MapPermissionsService(db));
  const args=[input.map,input.user];
  const cases=[
    ['markers-places',()=>service.findMarkersByMap(...args,{swLat:37,neLat:38,swLng:127,neLng:129,level:5})],
    ['markers-cells',()=>service.findMarkersByMap(...args,{swLat:37,neLat:38,swLng:127,neLng:129,level:6})],
    ['marker-group',()=>service.findMarkerGroup(...args,37.4444,127.8888)],
    ['marker-cluster',()=>service.findMarkerCluster(...args,{precision:3,cellLatKey:'37.444',cellLngKey:'127.889',limit:2})]
  ];
  const output=[];
  for(const [api,call] of cases){
    events=[];await call();const queries=events.filter(e=>/^\s*SELECT/i.test(e.sql));
    for(const query of queries){
      const plan=await db.$queryRawUnsafe('EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) '+query.sql,...query.params);
      output.push({api,...query,plan});
    }
  }
  console.log(JSON.stringify(output));
})().catch(e=>{console.log(JSON.stringify({error:String(e),stack:e.stack}))}).finally(()=>db.$disconnect());
