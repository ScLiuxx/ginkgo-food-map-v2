import { describe, expect, it } from 'vitest'
import type { MyRequestDetail } from '../api/myRestaurantRequestsApi'
import { contributionChanges, contributionFields } from './myContributionFields'

const proposed: Record<string, unknown> = {
  name: '临榆炸鸡腿',
  restaurantType: 'REGULAR',
  categoryCode: 'SNACK',
  categoryName: '小吃快餐',
  priceKind: 'UNKNOWN',
  pricePerPerson: null,
  address: '建设路',
  businessStatus: 'OPERATING',
  supportsDineIn: null,
  supportsDelivery: false,
  latitude: 30.67,
  longitude: 104.1,
  locationConfirmed: true,
  tags: ['炸鸡', '小吃'],
}
function detail(approved = { ...proposed }): MyRequestDetail {
  return {
    userId: '42',
    application: {
      id: '101',
      restaurantId: '501',
      requestType: 'CREATE',
      status: 'APPROVED',
      name: '临榆炸鸡腿',
      createdAt: '2026-11-01T00:00:00Z',
      reviewedAt: '2026-11-02T00:00:00Z',
      photoCount: 0,
    },
    proposed: { ...proposed },
    approved,
    rejectReason: null,
    targetAvailable: true,
    photoPositions: [],
  }
}

/** 验证真实内容变化的口径，避免默认值、顺序和管理端字段被误报为修改。 */
describe('本人投稿修改对照', () => {
  it('名称和消费变化计两项，对照保留原值，不改原投稿', () => {
    const source = detail({
      ...proposed,
      name: '临榆炸鸡腿（建设路店）',
      priceKind: 'EXACT',
      pricePerPerson: 18,
    })
    expect(contributionChanges(source)).toEqual([
      { key: 'name', label: '店铺名称', before: '临榆炸鸡腿', after: '临榆炸鸡腿（建设路店）' },
      { key: 'price', label: '人均消费', before: '未知', after: '¥18 / 人' },
    ])
    expect(source.proposed).toEqual(proposed)
  })
  it('相同分类编码的显示名、标签顺序和重复项、边缘空格、无意义金额及额外字段不算修改', () => {
    expect(
      contributionChanges(
        detail({
          ...proposed,
          name: ' 临榆炸鸡腿 ',
          categoryName: '分类改名',
          tags: ['小吃', '炸鸡', '小吃'],
          pricePerPerson: 20,
          reviewedBy: '管理端字段',
          photoIds: ['999'],
        }),
      ),
    ).toEqual([])
  })
  it('空值和缺失保持同义，不把未知营业状态当作修改', () => {
    const source = detail()
    source.proposed = {
      name: '',
      address: null,
      priceKind: 'UNKNOWN',
      tags: null,
      supportsDineIn: null,
      businessStatus: 'UNKNOWN',
    }
    source.approved = {
      name: null,
      address: '',
      priceKind: 'UNKNOWN',
      pricePerPerson: 10,
      tags: [],
    }
    expect(contributionChanges(source)).toEqual([])
  })
  it('分类、餐厅类型、预算类型、营业状态、用餐方式及标签按字段分组计数，忽略坐标', () => {
    const source = detail({
      ...proposed,
      categoryCode: 'OTHER',
      priceKind: 'ESTIMATE',
      pricePerPerson: 0,
      businessStatus: 'CLOSED',
      supportsDineIn: false,
      supportsDelivery: true,
      latitude: 30.68,
      longitude: 104.2,
      tags: ['新标签'],
    })
    expect(contributionChanges(source).map((row) => row.key)).toEqual([
      'category',
      'price',
      'businessStatus',
      'dining',
      'tags',
    ])
    expect(contributionChanges(source).find((row) => row.key === 'price')?.after).toBe('¥0 / 人')
    source.approved = { ...proposed, restaurantType: 'CANTEEN' }
    expect(contributionChanges(source).map((row) => row.key)).toEqual(['category'])
  })
  it('食堂不比较无关分类，保留用餐支持状态的差异，忽略位置确认', () => {
    const source = detail()
    source.proposed = { ...proposed, restaurantType: 'CANTEEN' }
    source.approved = {
      ...source.proposed,
      categoryCode: 'OTHER',
      supportsDineIn: false,
      locationConfirmed: false,
    }
    expect(contributionChanges(source).map((row) => row.key)).toEqual(['dining'])
    source.approved.supportsDineIn = source.proposed.supportsDineIn
    expect(contributionChanges(source)).toEqual([])
  })
  it.each(['PENDING', 'REJECTED', 'WITHDRAWN'] as const)('%s不产生管理员修改提示', (status) => {
    const source = detail({ ...proposed, name: '不同的名字' })
    source.application.status = status
    expect(contributionChanges(source)).toEqual([])
  })
  it('关联已有店铺的所有差异均不算管理员修改，历史空处理方式的普通通过仍能对照', () => {
    const source = detail({ ...proposed, name: '已有店铺名' })
    source.application.approvalMode = 'LINKED_EXISTING'
    expect(contributionChanges(source)).toEqual([])
    source.application.approvalMode = null
    expect(contributionChanges(source)).toHaveLength(1)
    source.approved = null
    expect(contributionChanges(source)).toEqual([])
  })
  it('缺失和无效数值不显示NaN，文字保持纯文本供Vue转义', () => {
    const rows = contributionFields({
      name: '<img src=x>',
      pricePerPerson: NaN,
      latitude: Infinity,
      longitude: 104.1,
    })
    expect(rows.find((row) => row.key === 'name')?.text).toBe('<img src=x>')
    expect(rows.find((row) => row.key === 'price')?.text).toBe('未知')
    expect(rows.find((row) => row.key === 'location')).toBeUndefined()
  })
})
