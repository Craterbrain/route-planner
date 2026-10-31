export const NAV_APPS = ['google', 'apple', 'waze'];

/** Deep link that starts turn-by-turn to a point in the driver's preferred app. */
export function navLink(app, { lat, lng }) {
  switch (app) {
    case 'google':
      return `google.navigation:q=${lat},${lng}&mode=d`;
    case 'apple':
      return `http://maps.apple.com/?daddr=${lat},${lng}&dirflg=d`;
    case 'waze':
      return `https://waze.com/ul?ll=${lat},${lng}&navigate=yes`;
    default:
      throw new Error(`unknown nav app: ${app}`);
  }
}
