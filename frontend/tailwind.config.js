// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
/** @type {import('tailwindcss').Config} */
export default {
  content: ['./index.html', './src/**/*.{js,ts,jsx,tsx}'],
  theme: {
    extend: {
      colors: {
        socle: {
          ink: '#0E0E10',
          slate: '#6B6B72',
          muted: '#9B9BA1',
          faint: '#B0B0B5',
          accent: '#3730E0',
          'accent-hover': '#2C27C7',
          mist: '#F0EFFC',
          line: '#ECECEE',
          soft: '#FAFAFB',
          success: '#1E8E5A',
          warn: '#B7791F',
          danger: '#B54708',
        },
      },
      fontFamily: {
        display: ['"Instrument Serif"', 'Georgia', 'serif'],
        sans: ['"IBM Plex Sans"', 'system-ui', 'sans-serif'],
        mono: ['"IBM Plex Mono"', 'ui-monospace', 'monospace'],
      },
    },
  },
  plugins: [],
}
