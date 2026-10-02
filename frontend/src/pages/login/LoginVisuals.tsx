// SPDX-License-Identifier: AGPL-3.0-or-later
/** Decorative tree SVG from Login.dc.html — identical paths. */
export function LoginVisualSvg() {
  return (
    <svg width="90%" height="90%" viewBox="0 0 600 500" aria-hidden="true">
      <line x1="300" y1="90" x2="150" y2="220" stroke="#2A2A30" strokeWidth="1.5" />
      <line x1="300" y1="90" x2="450" y2="220" stroke="#2A2A30" strokeWidth="1.5" />
      <line x1="150" y1="220" x2="90" y2="360" stroke="#2A2A30" strokeWidth="1.5" />
      <line x1="150" y1="220" x2="230" y2="360" stroke="#2A2A30" strokeWidth="1.5" />
      <line x1="450" y1="220" x2="390" y2="360" stroke="#2A2A30" strokeWidth="1.5" />
      <line x1="450" y1="220" x2="530" y2="360" stroke="#2A2A30" strokeWidth="1.5" />

      <rect x="240" y="62" width="120" height="46" rx="10" fill="#7C76FF" />
      <text
        x="300"
        y="90"
        textAnchor="middle"
        fontSize="13"
        fontFamily="IBM Plex Sans"
        fontWeight="600"
        fill="#0E0E10"
      >
        Socle
      </text>

      <rect x="95" y="196" width="110" height="42" rx="10" fill="none" stroke="#3A3A42" strokeWidth="1.5" />
      <rect x="395" y="196" width="110" height="42" rx="10" fill="none" stroke="#3A3A42" strokeWidth="1.5" />
      <rect x="40" y="338" width="100" height="40" rx="10" fill="none" stroke="#3A3A42" strokeWidth="1.5" />
      <rect x="180" y="338" width="100" height="40" rx="10" fill="none" stroke="#3A3A42" strokeWidth="1.5" />
      <rect x="340" y="338" width="100" height="40" rx="10" fill="none" stroke="#3A3A42" strokeWidth="1.5" />
      <rect x="480" y="338" width="100" height="40" rx="10" fill="none" stroke="#3A3A42" strokeWidth="1.5" />
    </svg>
  )
}

/** Decorative tree SVG from LoginError.dc.html — dashed orange « non provisionné » node. */
export function LoginErrorVisualSvg() {
  return (
    <svg width="90%" height="90%" viewBox="0 0 600 500" aria-hidden="true">
      <line x1="300" y1="90" x2="150" y2="220" stroke="#2A2A30" strokeWidth="1.5" />
      <line x1="300" y1="90" x2="450" y2="220" stroke="#2A2A30" strokeWidth="1.5" />
      <line x1="150" y1="220" x2="90" y2="360" stroke="#2A2A30" strokeWidth="1.5" />
      <line
        x1="150"
        y1="220"
        x2="230"
        y2="360"
        stroke="#2A2A30"
        strokeWidth="1.5"
        strokeDasharray="4 4"
      />
      <line x1="450" y1="220" x2="390" y2="360" stroke="#2A2A30" strokeWidth="1.5" />
      <line x1="450" y1="220" x2="530" y2="360" stroke="#2A2A30" strokeWidth="1.5" />

      <rect x="240" y="62" width="120" height="46" rx="10" fill="#7C76FF" />
      <text
        x="300"
        y="90"
        textAnchor="middle"
        fontSize="13"
        fontFamily="IBM Plex Sans"
        fontWeight="600"
        fill="#0E0E10"
      >
        Socle
      </text>

      <rect x="95" y="196" width="110" height="42" rx="10" fill="none" stroke="#3A3A42" strokeWidth="1.5" />
      <rect x="395" y="196" width="110" height="42" rx="10" fill="none" stroke="#3A3A42" strokeWidth="1.5" />
      <rect x="40" y="338" width="100" height="40" rx="10" fill="none" stroke="#3A3A42" strokeWidth="1.5" />
      <rect
        x="180"
        y="338"
        width="100"
        height="40"
        rx="10"
        fill="none"
        stroke="#B54708"
        strokeWidth="1.5"
        strokeDasharray="4 3"
      />
      <text
        x="230"
        y="362"
        textAnchor="middle"
        fontSize="10"
        fontFamily="IBM Plex Mono"
        fill="#B54708"
      >
        non provisionné
      </text>
      <rect x="340" y="338" width="100" height="40" rx="10" fill="none" stroke="#3A3A42" strokeWidth="1.5" />
      <rect x="480" y="338" width="100" height="40" rx="10" fill="none" stroke="#3A3A42" strokeWidth="1.5" />
    </svg>
  )
}

export function LockIcon({ stroke = '#43434A' }: { stroke?: string }) {
  return (
    <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke={stroke} strokeWidth="2" aria-hidden="true">
      <rect x="3" y="11" width="18" height="10" rx="2" />
      <path d="M7 11V7a5 5 0 0 1 10 0v4" />
    </svg>
  )
}

export function PasskeyIcon() {
  return (
    <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="#43434A" strokeWidth="2" aria-hidden="true">
      <rect x="4" y="4" width="16" height="16" rx="3" />
      <path d="M9 12l2 2 4-4" />
    </svg>
  )
}

export function AlertIcon() {
  return (
    <svg
      width="19"
      height="19"
      viewBox="0 0 24 24"
      fill="none"
      stroke="#B54708"
      strokeWidth="2"
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
    >
      <circle cx="12" cy="12" r="10" />
      <line x1="12" y1="8" x2="12" y2="13" />
      <line x1="12" y1="16" x2="12.01" y2="16" />
    </svg>
  )
}
