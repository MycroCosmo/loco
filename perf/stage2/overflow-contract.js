// E-12 known difference: only equal-count M cells may vary in P0's visible membership.
export function overflowMatches(body, oracle) {
  if (!Array.isArray(body) || body.length !== 700) return false;
  const seen = new Set(); let lat = oracle.latSum, lng = oracle.lngSum;
  for (let i=0;i<699;i++) {
    const m=body[i], expected=oracle.places[m.id];
    if (!expected || seen.has(m.id) || Object.keys(m).sort().join(',')!=='count,id,latitude,longitude,name,type'
        || m.type!=='place' || m.count!==1 || m.name!==expected.name
        || m.latitude!==expected.latitude || m.longitude!==expected.longitude) return false;
    seen.add(m.id); lat-=m.latitude; lng-=m.longitude;
  }
  const m=body[699], count=oracle.count-699;
  return Object.keys(m).sort().join(',')==='clusterKey,count,id,latitude,longitude,name,type'
    && m.type==='cluster' && m.id==='overflow' && m.clusterKey==='overflow'
    && m.name===`${count}개 장소` && m.count===count
    && Math.abs(m.latitude-lat/count)<1e-10 && Math.abs(m.longitude-lng/count)<1e-10;
}
