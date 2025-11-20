import { useEffect, useMemo, useRef, useState } from 'react'
import './App.css'

type LiveEvent = {
  type: string
  payload: string
}

type ConnectionState = 'connecting' | 'connected' | 'disconnected'

type TranscriptEntry = {
  id: string
  transcript: string
  summary: string
}

const MAX_SENTENCES = 12

const sanitizeSummaryToken = (token: string) =>
  token.replace(/\\n/g, ' ').replace(/\r/g, ' ').replace(/\n/g, ' ')

const joinSummaryToken = (current: string, token: string) => {
  const trimmed = token.trim()
  if (!trimmed) {
    return current
  }

  const noSpaceChars = '.,!?;:)'
  const prevChar = current.trimEnd().slice(-1)
  const firstChar = trimmed.charAt(0)
  const isLetter = (char: string) => /[A-Za-zÀ-ÖØ-öø-ÿ]/.test(char)
  const isLowerLetter = (char: string) => /[a-zà-öø-ÿ]/.test(char)

  const needsSpace =
    current.length > 0 &&
    !current.endsWith(' ') &&
    !noSpaceChars.includes(firstChar) &&
    firstChar !== '-' &&
    !(isLetter(prevChar) && isLowerLetter(firstChar))

  const next = `${needsSpace ? current + ' ' : current}${trimmed}`
  return next.replace(/\s{2,}/g, ' ')
}

const formatSummary = (text: string) =>
  text
    .replace(/\s+/g, ' ')
    .replace(/\s+([.,;:!?])/g, '$1')
    .replace(/([(\[])\s+/g, '$1')
    .replace(/\s+([)\]])/g, '$1')
    .replace(/\s+-\s+/g, '-')
    .trim()

const makeId = () => {
  if (typeof crypto !== 'undefined' && 'randomUUID' in crypto) {
    return crypto.randomUUID()
  }
  return `${Date.now()}-${Math.random().toString(16).slice(2)}`
}

function App() {
  const [partialTranscript, setPartialTranscript] = useState('')
  const [entries, setEntries] = useState<TranscriptEntry[]>([])
  const [connectionState, setConnectionState] = useState<ConnectionState>('connecting')
  const [error, setError] = useState<string | null>(null)
  const [activeSummaryId, setActiveSummaryId] = useState<string | null>(null)

  const summaryTargetRef = useRef<string | null>(null)
  useEffect(() => {
    summaryTargetRef.current = activeSummaryId
  }, [activeSummaryId])

  const streamUrl = useMemo(() => {
    const base = (import.meta.env.VITE_API_URL as string | undefined) ?? ''
    return `${base.replace(/\/$/, '')}/api/live/stream`.replace('//api', '/api')
  }, [])

  useEffect(() => {
    let source: EventSource | null = null
    let retryTimer: number | undefined

    const connect = () => {
      setConnectionState('connecting')
      setError(null)

      source = new EventSource(streamUrl)

      source.onopen = () => {
        setConnectionState('connected')
      }

      source.onerror = () => {
        setConnectionState('disconnected')
        source?.close()
        if (!retryTimer) {
          retryTimer = window.setTimeout(connect, 3000)
        }
      }

      source.onmessage = (event) => {
        try {
          const payload: LiveEvent = JSON.parse(event.data)
          handleLiveEvent(payload)
        } catch (e) {
          console.error('Impossible de traiter un event SSE', e)
        }
      }
    }

    const handleLiveEvent = (evt: LiveEvent) => {
      switch (evt.type) {
        case 'transcript-partial':
          setPartialTranscript(evt.payload)
          break
        case 'transcript-final': {
          setPartialTranscript('')
          const newEntry: TranscriptEntry = {
            id: makeId(),
            transcript: evt.payload,
            summary: '',
          }
          setEntries((prev) => [newEntry, ...prev].slice(0, MAX_SENTENCES))
          setActiveSummaryId(newEntry.id)
          break
        }
        case 'summary-start':
          setEntries((prev) =>
            prev.map((entry) =>
              entry.id === summaryTargetRef.current ? { ...entry, summary: '' } : entry,
            ),
          )
          break
        case 'summary-token': {
          const cleaned = sanitizeSummaryToken(evt.payload)
          if (!cleaned.trim()) {
            break
          }
          setEntries((prev) =>
            prev.map((entry) =>
              entry.id === summaryTargetRef.current
                ? { ...entry, summary: joinSummaryToken(entry.summary, cleaned) }
                : entry,
            ),
          )
          break
        }
        case 'summary-end':
          setActiveSummaryId(null)
          break
        case 'error':
          setError(evt.payload)
          break
        case 'status':
          if (evt.payload === 'listening') {
            setConnectionState('connected')
          }
          break
        default:
          break
      }
    }

    connect()

    return () => {
      if (retryTimer) {
        clearTimeout(retryTimer)
      }
      source?.close()
    }
  }, [streamUrl])

  const highlightedSummary = formatSummary(
    entries.find((entry) => entry.id === (activeSummaryId ?? entries[0]?.id))?.summary ?? '',
  )

  const liveDisplayText = partialTranscript || entries[0]?.transcript || 'Patiente, le micro se met en place...'

  return (
    <div className="page">
      <header className="hero">
        <div>
          <p className="eyebrow">Reconnaissance + résumé locaux</p>
          <h1>Flux live Abregé</h1>
          <p className="subtitle">
            Branche ton micro côté Spring, et suis en direct la transcription et le résumé générés localement par
            Ollama.
          </p>
        </div>
      </header>

      <main className="grid">
        <section className="panel live">
          <div className="panel-head live-head">
            <div>
              <h2>Flux micro</h2>
              <p className="live-label">Écoute en cours</p>
            </div>
            <div className={`status-pill compact ${connectionState}`}>
              <span className="dot" />
              {connectionState === 'connected' && 'Connecté'}
              {connectionState === 'connecting' && 'Connexion...'}
              {connectionState === 'disconnected' && 'Hors ligne'}
            </div>
          </div>
          {error && <div className="error-banner small">⚠️ {error}</div>}
          <p className="live-text">{liveDisplayText}</p>
        </section>

        <section className="panel summary">
          <div className="panel-head">
            <h2>Résumé Ollama</h2>
            <span>🧠 gemma3</span>
          </div>
          <p className="summary-text">
            {highlightedSummary || 'Le résumé apparaîtra dès qu’une phrase sera validée.'}
          </p>
        </section>

        <section className="panel sentences">
          <div className="panel-head">
            <h2>Transcriptions validées</h2>
            <span>{entries.length}</span>
          </div>
          <ul>
            {entries.length === 0 && <li className="empty">Aucune phrase détectée pour l’instant.</li>}
            {entries.map((entry, index) => (
              <li key={entry.id}>
                <span className="badge">#{entries.length - index}</span>
                <div className="sentence-content">
                  <p className="transcript">{entry.transcript}</p>
                  {entry.summary && <p className="summary-line">{formatSummary(entry.summary)}</p>}
                </div>
              </li>
            ))}
          </ul>
        </section>
      </main>
    </div>
  )
}

export default App
