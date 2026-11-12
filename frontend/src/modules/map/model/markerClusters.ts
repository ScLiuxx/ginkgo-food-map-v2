import type { MapPoint } from '../api/mapApi'

export interface MarkerCluster {
  points: MapPoint[]
  center: [number, number]
}

/** 仅聚合本批已加载点位，按屏幕像素判断密度；不把接口总匹配数冒充聚合数量。
 * 每轮合并最近的两组并重算屏幕重心，直到组间超过52像素，避免数字圆标互相遮挡。
 * 当前上限100家，可直接比较；选中店铺独立成组，保持列表点选后始终可见。
 */
export function clusterMapPoints(
  points: MapPoint[],
  project: (point: MapPoint) => { x: number; y: number },
  selected: string | null,
): MarkerCluster[] {
  const groups = points
    .filter((point) => point.id !== selected)
    .map((point) => ({ points: [point], ...project(point) }))
  while (groups.length > 1) {
    let closest = 52 ** 2
    let pair: [number, number] | undefined
    for (let i = 0; i < groups.length; i++) {
      for (let j = i + 1; j < groups.length; j++) {
        const left = groups[i]!,
          right = groups[j]!
        const distance = (left.x - right.x) ** 2 + (left.y - right.y) ** 2
        if (distance < closest || (!pair && distance === closest)) {
          closest = distance
          pair = [i, j]
        }
      }
    }
    if (!pair) break
    const left = groups[pair[0]]!,
      right = groups[pair[1]]!
    const count = left.points.length + right.points.length
    left.x = (left.x * left.points.length + right.x * right.points.length) / count
    left.y = (left.y * left.points.length + right.y * right.points.length) / count
    left.points.push(...right.points)
    groups.splice(pair[1], 1)
  }
  const active = points.find((point) => point.id === selected)
  if (active) groups.push({ points: [active], ...project(active) })
  return groups.map(({ points: members }) => ({
    points: members,
    center: [
      members.reduce((sum, point) => sum + point.longitude, 0) / members.length,
      members.reduce((sum, point) => sum + point.latitude, 0) / members.length,
    ],
  }))
}
