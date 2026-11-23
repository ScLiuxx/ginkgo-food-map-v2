export interface RestaurantMapLocation {
  name: string
  address: string
  center: [longitude: number, latitude: number]
}

/** 投稿使用字符串坐标，搜索结果使用数值。缺失或尚未确认的坐标不能落点。 */
export function toRestaurantMapLocation(place: {
  name: string
  address: string
  longitude: number | string | null
  latitude: number | string | null
  needsLocation?: boolean
}): RestaurantMapLocation | null {
  if (
    place.needsLocation ||
    !String(place.longitude ?? '').trim() ||
    !String(place.latitude ?? '').trim()
  )
    return null
  const longitude = Number(place.longitude)
  const latitude = Number(place.latitude)
  if (!(Math.abs(longitude) <= 180 && Math.abs(latitude) <= 90)) return null
  return { name: place.name, address: place.address, center: [longitude, latitude] }
}
