/** 接口评分仍为0–10；所有面向用户的分数按五分制显示，缺失及异常保持未知。 */
export function formatRating(score: number | null | undefined, digits = 1): string {
  if (score == null || !Number.isFinite(score) || score < 0 || score > 10) return '—'
  return (score / 2).toFixed(digits)
}
