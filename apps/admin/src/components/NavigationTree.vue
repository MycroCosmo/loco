<script setup lang="ts">
import type { MenuNode } from '../api/contracts'
import { screen } from '../screens'
defineProps<{ nodes: readonly MenuNode[]; parent: string | null }>()
</script>
<template>
  <ul><li v-for="node in nodes.filter(n => n.parentId === parent).sort((a,b) => a.sortOrder-b.sortOrder)" :key="node.id">
    <template v-if="node.type === 'PAGE'"><RouterLink v-if="screen(node.routeKey)" :to="screen(node.routeKey)?.path ?? '/access'">{{ node.title }}</RouterLink><span v-else>{{ node.title }} (등록되지 않은 화면)</span></template>
    <template v-else><span>{{ node.title }}</span><NavigationTree :nodes="nodes" :parent="node.id" /></template>
  </li></ul>
</template>
