/**
 * 音频预览组件
 */
interface Props {
  src: string
  label?: string
}

export default function AudioPreview({ src, label = '音频预览' }: Props) {
  return (
    <div className="audio-preview">
      <div className="audio-preview__label">{label}</div>
      <audio src={src} controls />
    </div>
  )
}
