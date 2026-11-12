import { describe, expect, it } from 'vitest'
import { clusterMapPoints } from './markerClusters'
import type { MapPoint } from '../api/mapApi'

const point = (id: string, longitude: number, latitude = 0): MapPoint => ({
  id,
  name: id,
  longitude,
  latitude,
  score: null,
  pricePerPerson: null,
  businessStatus: 'OPERATING',
})
const project = (value: MapPoint) => ({ x: value.longitude, y: value.latitude })

describe('已加载店铺的屏幕密度聚合', () => {
  it('近点合并、远点保留，成员完整且中心来自本组', () => {
    const points = [point('a', 0), point('b', 40), point('c', 200)]
    const groups = clusterMapPoints(points, project, null)
    expect(groups.map((group) => group.points.map((item) => item.id))).toEqual([['a', 'b'], ['c']])
    expect(groups[0]!.center).toEqual([20, 0])
    expect(points).toHaveLength(3)
  })
  it('不让相邻链条吞并整个区域，放大后可逐级分开', () => {
    const points = [point('a', 0), point('b', 40), point('c', 80)]
    expect(clusterMapPoints(points, project, null).map((group) => group.points.length)).toEqual([
      2, 1,
    ])
    expect(
      clusterMapPoints(points, (value) => ({ x: value.longitude * 4, y: 0 }), null),
    ).toHaveLength(3)
  })
  it('同坐标100家保留全部成员，选中店铺独立且不重复计数', () => {
    const points = Array.from({ length: 100 }, (_, index) => point(String(index), 104, 30))
    const groups = clusterMapPoints(points, project, '71')
    expect(groups.map((group) => group.points.length)).toEqual([99, 1])
    expect(groups[1]!.points[0]!.id).toBe('71')
    expect(new Set(groups.flatMap((group) => group.points.map((item) => item.id))).size).toBe(100)
  })
  it('空集和不在本批的选中ID不产生虚假点位', () => {
    expect(clusterMapPoints([], project, 'missing')).toEqual([])
    expect(clusterMapPoints([point('a', 0)], project, 'missing')).toHaveLength(1)
  })
  it('按重心复核组间距，避免两组数字圆标互相遮挡', () => {
    const groups = clusterMapPoints(
      [point('a', 0), point('b', 50), point('c', 53), point('d', 54), point('e', 55)],
      project,
      null,
    )
    expect(groups).toHaveLength(2)
    expect(Math.abs(groups[0]!.center[0] - groups[1]!.center[0])).toBeGreaterThan(52)
    expect(groups.flatMap((group) => group.points)).toHaveLength(5)
  })
})
