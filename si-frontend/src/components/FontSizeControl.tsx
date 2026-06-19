import { TRANSCRIPT_FONT_SCALES } from '../constants'
import './FontSizeControl.css'

interface FontSizeControlProps {
  /** 当前字号档位值 */
  scale: number
  /** 切换字号档位 */
  onChange: (value: number) => void
}

/**
 * 同传文本字号控制器：用于使用界面与共享页，调整 ASR 原文与译文的字体大小。
 */
export default function FontSizeControl({ scale, onChange }: FontSizeControlProps) {
  return (
    <div className="si-font-size-control" role="group" aria-label="文本字号">
      <span className="si-font-size-label">字号</span>
      {TRANSCRIPT_FONT_SCALES.map(option => (
        <button
          key={option.value}
          type="button"
          className={`si-font-size-btn${scale === option.value ? ' is-active' : ''}`}
          aria-pressed={scale === option.value}
          onClick={() => onChange(option.value)}
        >
          {option.label}
        </button>
      ))}
    </div>
  )
}
