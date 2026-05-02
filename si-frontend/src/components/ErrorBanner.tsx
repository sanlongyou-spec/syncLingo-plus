/**
 * 错误提示横幅组件
 */
interface Props {
  message: string
  onDismiss?: () => void
}

export default function ErrorBanner({ message, onDismiss }: Props) {
  if (!message) return null
  return (
    <div className="error-banner" role="alert">
      <span className="error-banner__message">{message}</span>
      {onDismiss && (
        <button className="error-banner__close" onClick={onDismiss} type="button">
          ×
        </button>
      )}
    </div>
  )
}
