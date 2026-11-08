import type { MenuNode } from '../api/contracts'

export function outline(nodes: readonly MenuNode[], parent: string | null = null, depth = 0): {node:MenuNode;depth:number}[] {
  return nodes.filter(n=>n.parentId===parent).sort((a,b)=>a.sortOrder-b.sortOrder)
    .flatMap(node=>[{node,depth},...outline(nodes,node.id,depth+1)])
}
export function excludedParents(nodes: readonly MenuNode[], id: string): Set<string> {
  return new Set([id,...outline(nodes,id).map(item=>item.node.id)])
}
export function adjacentPosition(nodes:readonly MenuNode[], id:string, direction:'up'|'down') {
  const selected=nodes.find(n=>n.id===id)
  if(!selected) return null
  const siblings=nodes.filter(n=>n.parentId===selected.parentId).sort((a,b)=>a.sortOrder-b.sortOrder)
  const at=siblings.findIndex(n=>n.id===id)
  if(direction==='up') return at>0?{parentId:selected.parentId,beforeSiblingId:siblings[at-1]?.id??null}:null
  return at<siblings.length-1?{parentId:selected.parentId,beforeSiblingId:siblings[at+2]?.id??null}:null
}
