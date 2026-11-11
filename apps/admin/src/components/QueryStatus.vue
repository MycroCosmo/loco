<script setup lang="ts">
defineProps<{ status: 'idle' | 'loading' | 'ready' | 'failed'; hasResult: boolean; empty: boolean }>()
</script>
<template>
  <p v-if="status === 'loading'" role="status">조회 중입니다.</p>
  <p v-else-if="status === 'failed'" role="status">{{ hasResult ? '다시 조회하지 못했습니다. 아래는 마지막 조회에 성공한 이전 결과입니다. 조건을 확인하고 다시 조회하세요.' : '조회하지 못했습니다. 결과가 없는 상태가 아닙니다. 잠시 후 다시 조회하세요.' }}</p>
  <p v-else-if="status === 'ready' && empty" role="status">적용된 조건에 맞는 결과가 없습니다.</p>
</template>
