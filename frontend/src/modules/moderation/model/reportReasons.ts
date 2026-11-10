/** 举报原因与后端固定枚举一一对应；界面只展示中文文案，不把枚举外文本回传服务端。 */
export const reportReasons = [
  { value: 'SPAM', label: '广告或垃圾信息' },
  { value: 'ABUSE', label: '辱骂或人身攻击' },
  { value: 'PORNOGRAPHY', label: '色情低俗' },
  { value: 'ILLEGAL', label: '违法违规' },
  { value: 'PRIVACY', label: '泄露隐私' },
  { value: 'IRRELEVANT', label: '无关内容' },
  { value: 'OTHER', label: '其他' },
] as const

export type ReportReason = (typeof reportReasons)[number]['value']

export const reportReasonLabel = (value: string) =>
  reportReasons.find((reason) => reason.value === value)?.label ?? '其他'

/** 举报结果通知与列表共用同一组处置结论文案，避免不同页面出现语义差异。 */
export const reportDecisionLabel = (decision: 'REMOVED' | 'DISMISSED') =>
  decision === 'REMOVED' ? '举报已处理：内容已删除' : '举报未认定违规'

export const reportTargetLabel = (target: 'REVIEW' | 'REPLY') =>
  target === 'REVIEW' ? '点评' : '回复'
