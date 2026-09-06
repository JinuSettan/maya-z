/**
 * Semantic design tokens for the mobile app.
 *
 * These tokens mirror the naming conventions used in web artifacts (index.css)
 * so that multi-artifact projects share a cohesive visual identity.
 *
 * Replace the placeholder values below with values that match the project's
 * brand. If a sibling web artifact exists, read its index.css and convert the
 * HSL values to hex so both artifacts use the same palette.
 *
 * To add dark mode, add a `dark` key with the same token names.
 * The useColors() hook will automatically pick it up.
 */

const colors = {
  light: {
    text: '#ecebed',
    tint: '#ff791f',

    background: '#0b0b0d',
    foreground: '#ecebed',

    card: '#111114',
    cardForeground: '#ecebed',

    primary: '#ff791f',
    primaryForeground: '#fbfafc',

    secondary: '#1f1f23',
    secondaryForeground: '#ecebed',

    muted: '#1f1f23',
    mutedForeground: '#97939c',

    accent: '#ff791f',
    accentForeground: '#fbfafc',

    // Destructive actions (delete, error states)
    destructive: '#ef4444',
    destructiveForeground: '#fbfafc',

    border: '#222226',
    input: '#29292e',
  },

  // Matches Maya's web radius: 0.75rem.
  radius: 12,
};

export default colors;
