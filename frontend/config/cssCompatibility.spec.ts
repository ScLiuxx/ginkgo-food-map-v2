import { describe, expect, it } from 'vitest'

import { assertLegacyMediaQueries } from './cssCompatibility'

describe('production CSS compatibility', () => {
  it('accepts traditional breakpoints and unrelated comparison text', () => {
    expect(() =>
      assertLegacyMediaQueries(
        '@media (min-width: 601px) and (max-width: 1000px){a::after{content:"a>=b"}}',
        'home.css',
      ),
    ).not.toThrow()
  })

  it.each(['(width<=700px)', '(700px > width)', '(600px <= width <= 900px)', '(width=700px)'])(
    'rejects unsupported range breakpoint %s and identifies the asset',
    (query) => {
      expect(() => assertLegacyMediaQueries(`@media ${query}{a{color:red}}`, 'home.css')).toThrow(
        'home.css: unsupported media range syntax',
      )
    },
  )
})
