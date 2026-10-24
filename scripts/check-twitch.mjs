// Optional live smoke check; never logs or saves playback tokens or signed URLs.
const headers = {
  'Client-ID': 'kimne78kx3ncx6brgo4mv6wki5h1ko',
  'Content-Type': 'application/json',
  Origin: 'https://www.twitch.tv',
  Referer: 'https://www.twitch.tv/',
};
const hash = 'ed230aa1e33e07eebb8928504583da78a5173989fadfb1ac94be06a04f3cdbe9';
const candidates = process.argv.slice(2);
if (!candidates.length) candidates.push('lck', 'riotgames', 'shroud', 'jynxzi', 'caseoh_', 'rubius', 'ponce', 'nasa');
let verified = false;
for (const login of candidates) {
  if (!/^[a-z0-9_]+$/i.test(login)) throw new Error('Invalid channel name');
  const response = await fetch('https://gql.twitch.tv/gql', {
    method: 'POST', headers,
    body: JSON.stringify({ operationName: 'PlaybackAccessToken',
      extensions: { persistedQuery: { version: 1, sha256Hash: hash } },
      variables: { isLive: true, login, isVod: false, vodID: '', playerType: 'embed', platform: 'site' } }),
    signal: AbortSignal.timeout(20000),
  });
  const payload = await response.json();
  const token = payload.data?.streamPlaybackAccessToken;
  if (!response.ok || !token) throw new Error(`Playback token failed: HTTP ${response.status}`);
  const url = new URL(`https://usher.ttvnw.net/api/v2/channel/hls/${login}.m3u8`);
  url.search = new URLSearchParams({ sig: token.signature, token: token.value, allow_source: 'true',
    allow_audio_only: 'true', platform: 'web', supported_codecs: 'h264,h265,av1', fast_bread: 'true',
    playlist_include_framerate: 'true', p: String(Math.floor(Math.random() * 999999)) });
  const playlist = await fetch(url, { headers: { Origin: headers.Origin, Referer: headers.Referer }, signal: AbortSignal.timeout(20000) });
  const text = await playlist.text();
  if (playlist.status === 404) { console.log(`${login}: offline (404)`); continue; }
  if (!playlist.ok || !text.startsWith('#EXTM3U')) throw new Error(`HLS failed: HTTP ${playlist.status}`);
  console.log(`${login}: HLS OK, ${(text.match(/#EXT-X-STREAM-INF/g) || []).length} variants`);
  const lines = text.split(/\r?\n/);
  const variant = lines.find(line => line.startsWith('https://'));
  if (!variant) throw new Error('No variant URL found');
  const media = await fetch(variant, { headers: { Origin: headers.Origin, Referer: headers.Referer }, signal: AbortSignal.timeout(20000) });
  const mediaText = await media.text();
  if (!media.ok || !mediaText.startsWith('#EXTM3U')) throw new Error(`Media playlist failed: HTTP ${media.status}`);
  console.log('Media playlist: OK');
  const segment = mediaText.split(/\r?\n/).find(line => line && !line.startsWith('#'));
  if (segment) {
    const chunk = await fetch(new URL(segment, variant), { headers: { Origin: headers.Origin, Referer: headers.Referer }, signal: AbortSignal.timeout(20000) });
    if (!chunk.ok) throw new Error(`Segment failed: HTTP ${chunk.status}`);
    console.log(`Media segment: OK (${(await chunk.arrayBuffer()).byteLength} bytes)`);
  }
  verified = true;
  break;
}
if (!verified) throw new Error('No live channel found; rerun with a channel currently live.');
