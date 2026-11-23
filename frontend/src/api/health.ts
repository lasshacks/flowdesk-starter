export interface HealthResponse {
  status: string
}

export async function getHealth(): Promise<HealthResponse> {
  const response = await fetch('/api/health', {
    headers: { Accept: 'application/json' },
    signal: AbortSignal.timeout(5000),
  })

  if (!response.ok) {
    throw new Error(`서버 상태 조회에 실패했습니다. (HTTP ${response.status})`)
  }

  const data: unknown = await response.json()
  if (
    typeof data !== 'object' ||
    data === null ||
    !('status' in data) ||
    typeof data.status !== 'string' ||
    data.status.length === 0
  ) {
    throw new Error('서버 응답 형식이 올바르지 않습니다.')
  }

  return { status: data.status }
}
