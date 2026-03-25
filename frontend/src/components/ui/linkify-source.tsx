/**
 * 출처 텍스트에서 URL을 감지하여 클릭 가능한 링크로 변환
 */
export function LinkifySource({ text }: { text: string }) {
  // (출처: URL) 패턴 감지
  const sourceMatch = text.match(/\(출처:\s*(https?:\/\/[^\s)]+)\)/);
  // 일반 URL 패턴 감지
  const urlMatch = text.match(/(https?:\/\/[^\s)]+)/);

  const url = sourceMatch?.[1] || urlMatch?.[1];

  if (!url) {
    return <span>{text}</span>;
  }

  // URL 부분을 링크로 교체
  const beforeUrl = text.substring(0, text.indexOf(url));
  const afterUrl = text.substring(text.indexOf(url) + url.length);

  return (
    <span>
      {beforeUrl}
      <a href={url} target="_blank" rel="noopener noreferrer"
         className="text-blue-600 hover:text-blue-800 underline underline-offset-2">
        {url.length > 60 ? url.substring(0, 60) + '...' : url}
      </a>
      {afterUrl}
    </span>
  );
}
