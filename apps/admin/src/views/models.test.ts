import { expect, it } from 'vitest'
import { adjacentPosition, excludedParents, outline } from './menuModel'
import { historyFilters } from './historyModel'
import type { MenuNode } from '../api/contracts'
import { historyEntry } from '../api/contracts'

const nodes:MenuNode[]=[
  {id:'A',parentId:null,sortOrder:0,title:'A',type:'GROUP',routeKey:null,permissionKey:null,visible:true},
  {id:'child',parentId:'A',sortOrder:0,title:'Child',type:'GROUP',routeKey:null,permissionKey:null,visible:true},
  {id:'leaf',parentId:'child',sortOrder:0,title:'Leaf',type:'PAGE',routeKey:'cms.files',permissionKey:'cms.files',visible:true},
  {id:'B',parentId:null,sortOrder:1,title:'B',type:'GROUP',routeKey:null,permissionKey:null,visible:true},
  {id:'C',parentId:null,sortOrder:2,title:'C',type:'GROUP',routeKey:null,permissionKey:null,visible:true},
]
it('keeps IDs and depth when displaying a subtree',()=>{
  expect(outline(nodes).map(r=>[r.node.id,r.depth])).toEqual([['A',0],['child',1],['leaf',2],['B',0],['C',0]])
})
it('excludes self and all descendants as candidate parents',()=>{
  expect([...excludedParents(nodes,'A')]).toEqual(['A','child','leaf'])
})
it('expresses up/down as beforeSiblingId rather than rewriting IDs or sort numbers',()=>{
  expect(adjacentPosition(nodes,'B','up')).toEqual({parentId:null,beforeSiblingId:'A'})
  expect(adjacentPosition(nodes,'A','down')).toEqual({parentId:null,beforeSiblingId:'C'})
  expect(adjacentPosition(nodes,'B','down')).toEqual({parentId:null,beforeSiblingId:null})
  expect(adjacentPosition(nodes,'A','up')).toBeNull()
  expect(adjacentPosition(nodes,'C','down')).toBeNull()
})
it('converts explicitly Korean time to UTC and omits blank filters',()=>{
  const params=new URLSearchParams(historyFilters({resourceType:' file ',resourceId:'',actorId:'',from:'2026-11-08T09:00',until:'2026-11-08T10:00'}))
  expect(params.get('resourceType')).toBe('file')
  expect(params.has('actorId')).toBe(false)
  expect(params.get('from')).toBe('2026-11-08T00:00:00.000Z')
  expect(params.get('until')).toBe('2026-11-08T01:00:00.000Z')
})
it('rejects equal or reversed history time bounds',()=>{
  expect(()=>historyFilters({resourceType:'',resourceId:'',actorId:'',from:'2026-11-08T10:00',until:'2026-11-08T10:00'})).toThrow()
})
it('accepts bootstrap history with a nullable actor and before snapshot',()=>{
  const record=historyEntry({id:'history',actorId:null,action:'CREATE',resourceType:'admin-account',resourceId:'admin',occurredAt:'2026-11-08T00:00:00Z',before:null,after:{loginId:'root'},payloadVersion:1})
  expect(record.actorId).toBeNull()
  expect(record.after).toEqual({loginId:'root'})
})
