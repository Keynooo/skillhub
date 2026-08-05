import { Link, useNavigate, useSearch } from '@tanstack/react-router'
import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { ApiError, authApi } from '@/api/client'
import { LoginButton } from '@/features/auth/login-button'
import { useLocalRegister } from '@/features/auth/use-local-auth'
import { Button } from '@/shared/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/shared/ui/card'
import { Input } from '@/shared/ui/input'
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/shared/ui/tabs'

const USERNAME_PATTERN = /^[A-Za-z0-9_]{3,64}$/
const EMAIL_PATTERN = /^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}$/
const COOLDOWN_SECONDS = 5

type RegisterFieldErrors = {
  username?: string
  email?: string
  password?: string
  confirmPassword?: string
  code?: string
}

function isDuplicateUsernameError(errorKey: string) {
  return errorKey === 'error.auth.local.username.exists'
    || errorKey.includes('Username already exists')
    || errorKey.includes('用户名已存在')
}

function isDuplicateEmailError(errorKey: string) {
  return errorKey === 'error.auth.local.email.exists'
    || errorKey.includes('Email already exists')
    || errorKey.includes('邮箱已存在')
}

/**
 * Registration page for local accounts with an alternate OAuth-based entry path.
 */
export function RegisterPage() {
  const { t } = useTranslation()
  const navigate = useNavigate()
  const search = useSearch({ from: '/register' })
  const registerMutation = useLocalRegister()
  const [username, setUsername] = useState('')
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [confirmPassword, setConfirmPassword] = useState('')
  const [code, setCode] = useState('')
  const [fieldErrors, setFieldErrors] = useState<RegisterFieldErrors>({})
  const [formError, setFormError] = useState<string | null>(null)
  const [isSendingCode, setIsSendingCode] = useState(false)
  const [resendCooldown, setResendCooldown] = useState(0)
  const [codeSentMessage, setCodeSentMessage] = useState<string | null>(null)

  const returnTo = search.returnTo && search.returnTo.startsWith('/') ? search.returnTo : '/dashboard'

  useEffect(() => {
    if (resendCooldown <= 0) return
    const timer = setInterval(() => {
      setResendCooldown((previous) => (previous <= 1 ? 0 : previous - 1))
    }, 1000)
    return () => clearInterval(timer)
  }, [resendCooldown])

  function validateUsername(value: string) {
    const trimmed = value.trim()
    if (!trimmed) return t('register.usernameRequired')
    if (!USERNAME_PATTERN.test(trimmed)) return t('register.usernameInvalid')
    return undefined
  }

  function validateEmail(value: string) {
    const trimmed = value.trim().toLowerCase()
    if (!trimmed) return t('register.emailRequired')
    if (!EMAIL_PATTERN.test(trimmed)) return t('register.emailInvalid')
    return undefined
  }

  function validatePassword(value: string) {
    if (!value) return t('register.passwordRequired')
    if (value.length < 6) return t('register.passwordTooShort')
    return undefined
  }

  function validateConfirmPassword(value: string) {
    if (!value) return t('register.confirmPasswordRequired')
    if (value !== password) return t('register.passwordMismatch')
    return undefined
  }

  function validateCode(value: string) {
    if (!value.trim()) return t('register.codeRequired')
    return undefined
  }

  // Real-time username availability check on blur.
  async function checkUsernameAvailability(value: string) {
    const trimmed = value.trim()
    if (!trimmed || !USERNAME_PATTERN.test(trimmed)) return
    try {
      const available = await authApi.checkUsername(trimmed)
      setFieldErrors((current) => ({
        ...current,
        username: available ? undefined : t('register.usernameExists'),
      }))
    } catch {
      // Ignore lookup failures — final check happens at submit on the server.
    }
  }

  function mapRegisterApiError(error: unknown): { fieldErrors?: RegisterFieldErrors, formError?: string } {
    if (!(error instanceof ApiError)) {
      return { formError: error instanceof Error ? error.message : t('apiError.unknown') }
    }
    const errorKey = error.serverMessageKey ?? error.serverMessage ?? error.message
    switch (errorKey) {
      case 'validation.auth.local.username.notBlank':
        return { fieldErrors: { username: t('register.usernameRequired') } }
      case 'validation.auth.local.password.notBlank':
        return { fieldErrors: { password: t('register.passwordRequired') } }
      case 'validation.auth.local.email.notBlank':
        return { fieldErrors: { email: t('register.emailRequired') } }
      case 'validation.auth.local.email.invalid':
        return { fieldErrors: { email: t('register.emailInvalid') } }
      case 'error.auth.local.username.invalid':
        return { fieldErrors: { username: t('register.usernameInvalid') } }
      case 'error.auth.local.password.tooShort':
        return { fieldErrors: { password: t('register.passwordTooShort') } }
      case 'error.auth.local.password.tooWeak':
        return { fieldErrors: { password: t('register.passwordTooWeak') } }
      case 'error.auth.local.username.exists':
        return { fieldErrors: { username: t('register.usernameExists') } }
      case 'error.auth.local.email.exists':
        return { fieldErrors: { email: t('register.emailExists') } }
      case 'error.auth.local.activation.invalid.code':
        return { fieldErrors: { code: t('register.codeInvalid') } }
      default:
        if (isDuplicateUsernameError(errorKey)) {
          return { fieldErrors: { username: t('register.usernameExists') } }
        }
        if (isDuplicateEmailError(errorKey)) {
          return { fieldErrors: { email: t('register.emailExists') } }
        }
        return { formError: error.serverMessage || error.message || t('apiError.unknown') }
    }
  }

  async function handleSendCode() {
    const trimmedEmail = email.trim().toLowerCase()
    const emailError = validateEmail(trimmedEmail)
    if (emailError) {
      setFieldErrors((current) => ({ ...current, email: emailError }))
      return
    }
    setIsSendingCode(true)
    setFieldErrors((current) => ({ ...current, email: undefined }))
    setFormError(null)
    setCodeSentMessage(null)
    try {
      await authApi.sendRegistrationCode({ email: trimmedEmail })
      setCodeSentMessage(t('register.codeSent'))
      setResendCooldown(COOLDOWN_SECONDS)
    } catch (error) {
      const { fieldErrors: nextApiFieldErrors, formError: nextFormError } = mapRegisterApiError(error)
      setFieldErrors(nextApiFieldErrors ?? {})
      setFormError(nextFormError ?? null)
    } finally {
      setIsSendingCode(false)
    }
  }

  async function handleSubmit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const trimmedUsername = username.trim()
    const trimmedEmail = email.trim().toLowerCase()
    const nextFieldErrors: RegisterFieldErrors = {}

    nextFieldErrors.username = validateUsername(username)
    nextFieldErrors.email = validateEmail(email)
    nextFieldErrors.password = validatePassword(password)
    nextFieldErrors.confirmPassword = validateConfirmPassword(confirmPassword)
    nextFieldErrors.code = validateCode(code)

    if (Object.values(nextFieldErrors).some(Boolean)) {
      setFieldErrors(nextFieldErrors)
      setFormError(null)
      registerMutation.reset()
      return
    }

    setFieldErrors({})
    setFormError(null)
    try {
      await registerMutation.mutateAsync({ username: trimmedUsername, email: trimmedEmail, password, code })
      await navigate({ to: returnTo })
    } catch (error) {
      const { fieldErrors: nextApiFieldErrors, formError: nextFormError } = mapRegisterApiError(error)
      setFieldErrors(nextApiFieldErrors ?? {})
      setFormError(nextFormError ?? null)
    }
  }

  return (
    <div className="mx-auto flex min-h-[70vh] max-w-2xl items-center justify-center">
      <Card className="w-full border-slate-200 bg-white/95 shadow-xl">
        <CardHeader className="space-y-3 text-center">
          <CardTitle>{t('register.title')}</CardTitle>
          <CardDescription>{t('register.subtitle')}</CardDescription>
        </CardHeader>
        <CardContent>
          <Tabs defaultValue="local" className="space-y-6">
            <TabsList className="grid w-full grid-cols-2">
              <TabsTrigger value="local">{t('register.tabLocal')}</TabsTrigger>
              <TabsTrigger value="oauth">{t('register.tabOAuth')}</TabsTrigger>
            </TabsList>

            <TabsContent value="local">
              <form className="space-y-4" onSubmit={handleSubmit}>
                <div className="space-y-2">
                  <label className="text-sm font-medium" htmlFor="register-username">{t('register.username')}</label>
                  <Input
                    id="register-username"
                    autoComplete="username"
                    value={username}
                    onChange={(event) => {
                      setUsername(event.target.value)
                      if (fieldErrors.username || formError) {
                        setFieldErrors((current) => ({ ...current, username: undefined }))
                        setFormError(null)
                        registerMutation.reset()
                      }
                    }}
                    placeholder={t('register.usernamePlaceholder')}
                    aria-invalid={fieldErrors.username ? 'true' : 'false'}
                    onBlur={() => {
                      const formatError = validateUsername(username)
                      setFieldErrors((current) => ({ ...current, username: formatError }))
                      if (!formatError) {
                        checkUsernameAvailability(username)
                      }
                    }}
                  />
                  {fieldErrors.username ? <p className="text-sm text-red-600">{fieldErrors.username}</p> : null}
                </div>
                <div className="space-y-2">
                  <label className="text-sm font-medium" htmlFor="register-email">{t('register.email')}</label>
                  <div className="flex flex-col gap-2 sm:flex-row">
                    <Input
                      id="register-email"
                      type="email"
                      autoComplete="email"
                      value={email}
                      onChange={(event) => {
                        setEmail(event.target.value)
                        if (fieldErrors.email || formError) {
                          setFieldErrors((current) => ({ ...current, email: undefined }))
                          setFormError(null)
                          registerMutation.reset()
                        }
                      }}
                      placeholder={t('register.emailPlaceholder')}
                      aria-invalid={fieldErrors.email ? 'true' : 'false'}
                      onBlur={() => {
                        setFieldErrors((current) => ({ ...current, email: validateEmail(email) }))
                      }}
                    />
                    <Button
                      className="sm:w-auto"
                      disabled={isSendingCode || registerMutation.isPending || resendCooldown > 0}
                      type="button"
                      variant="outline"
                      onClick={handleSendCode}
                    >
                      {isSendingCode
                        ? t('register.sendingCode')
                        : resendCooldown > 0
                          ? t('register.resendCooldown', { seconds: resendCooldown })
                          : t('register.sendCode')}
                    </Button>
                  </div>
                  {fieldErrors.email ? <p className="text-sm text-red-600">{fieldErrors.email}</p> : null}
                  {codeSentMessage ? <p className="text-sm text-emerald-700">{codeSentMessage}</p> : null}
                </div>
                <div className="space-y-2">
                  <label className="text-sm font-medium" htmlFor="register-password">{t('register.password')}</label>
                  <Input
                    id="register-password"
                    type="password"
                    autoComplete="new-password"
                    value={password}
                    onChange={(event) => {
                      setPassword(event.target.value)
                      if (fieldErrors.password || formError) {
                        setFieldErrors((current) => ({ ...current, password: undefined }))
                        setFormError(null)
                        registerMutation.reset()
                      }
                    }}
                    placeholder={t('register.passwordPlaceholder')}
                    aria-invalid={fieldErrors.password ? 'true' : 'false'}
                    onBlur={() => {
                      setFieldErrors((current) => ({ ...current, password: validatePassword(password) }))
                    }}
                  />
                  {fieldErrors.password ? <p className="text-sm text-red-600">{fieldErrors.password}</p> : null}
                </div>
                <div className="space-y-2">
                  <label className="text-sm font-medium" htmlFor="register-confirm-password">{t('register.confirmPassword')}</label>
                  <Input
                    id="register-confirm-password"
                    type="password"
                    autoComplete="new-password"
                    value={confirmPassword}
                    onChange={(event) => {
                      setConfirmPassword(event.target.value)
                      if (fieldErrors.confirmPassword || formError) {
                        setFieldErrors((current) => ({ ...current, confirmPassword: undefined }))
                        setFormError(null)
                        registerMutation.reset()
                      }
                    }}
                    placeholder={t('register.confirmPasswordPlaceholder')}
                    aria-invalid={fieldErrors.confirmPassword ? 'true' : 'false'}
                    onBlur={() => {
                      setFieldErrors((current) => ({ ...current, confirmPassword: validateConfirmPassword(confirmPassword) }))
                    }}
                  />
                  {fieldErrors.confirmPassword ? <p className="text-sm text-red-600">{fieldErrors.confirmPassword}</p> : null}
                </div>
                <div className="space-y-2">
                  <label className="text-sm font-medium" htmlFor="register-code">{t('register.code')}</label>
                  <Input
                    id="register-code"
                    value={code}
                    onChange={(event) => {
                      setCode(event.target.value)
                      if (fieldErrors.code || formError) {
                        setFieldErrors((current) => ({ ...current, code: undefined }))
                        setFormError(null)
                        registerMutation.reset()
                      }
                    }}
                    placeholder={t('register.codePlaceholder')}
                    autoComplete="one-time-code"
                    aria-invalid={fieldErrors.code ? 'true' : 'false'}
                  />
                  {fieldErrors.code ? <p className="text-sm text-red-600">{fieldErrors.code}</p> : null}
                </div>
                {formError ? <p className="text-sm text-red-600">{formError}</p> : null}
                <Button className="w-full" disabled={registerMutation.isPending} type="submit">
                  {registerMutation.isPending ? t('register.submitting') : t('register.submit')}
                </Button>
                <p className="text-center text-sm text-muted-foreground">
                  {t('register.hasAccount')}
                  {' '}
                  <Link
                    to="/login"
                    search={{ returnTo }}
                    className="font-medium text-primary hover:underline"
                  >
                    {t('register.login')}
                  </Link>
                </p>
              </form>
            </TabsContent>

            <TabsContent value="oauth" className="space-y-4">
              <p className="text-sm text-muted-foreground">
                {t('register.oauthHint')}
              </p>
              <LoginButton returnTo={returnTo} />
            </TabsContent>
          </Tabs>
        </CardContent>
      </Card>
    </div>
  )
}
