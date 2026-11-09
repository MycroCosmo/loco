// Frozen before P2 timing. Invalid runs stay in the evidence; never auto-retry.
export const interferencePolicy={backgroundCpuCores:2,sustainedSeconds:10,minAvailableBytes:2*1024**3,maxSampleGapSeconds:10,
  expectedProcesses:['vmmemWSL','vmmem','com.docker.backend','collector PID'],
  note:'Detection is a conservative proxy, not proof of absent interference; names/PIDs/resources only, no command lines'};
export function assessInterference(host,resources=[]) {
  const reasons=new Set();let cpu=0,memory=0,previous;
  if(host.length<2)reasons.add('host-samples-missing');
  for(const h of host) {
    const delta=previous?Math.max(0,(Date.parse(h.at)-Date.parse(previous.at))/1000):0;
    if(delta>interferencePolicy.maxSampleGapSeconds)reasons.add('host-collector-gap');
    if(h.processCount<10)reasons.add('host-process-visibility-incomplete');
    if(h.ac!==1||h.overlay!=='ded574b5-45a0-4f42-8737-46345c09c238')reasons.add('power-condition-changed');
    cpu=h.nonWorkloadCpuCores>interferencePolicy.backgroundCpuCores?cpu+delta:0;
    memory=h.availablePhysicalBytes<interferencePolicy.minAvailableBytes?memory+delta:0;
    if(cpu>=interferencePolicy.sustainedSeconds)reasons.add('sustained-background-cpu');
    if(memory>=interferencePolicy.sustainedSeconds)reasons.add('host-memory-pressure');
    previous=h;
  }
  for(const r of resources) {
    if(r.error)reasons.add('resource-collector-error');
    if((r.stats||[]).some(s=>s.name.startsWith('background-')))reasons.add('unrelated-container-running');
  }
  return {valid:reasons.size===0,reasons:[...reasons],policy:interferencePolicy,hostSamples:host.length};
}
