export type MayaLanguage = {
  code: string;
  label: string;
};

const LANGUAGE_LABELS: Record<string, string> = {
  en: 'English',
  ml: 'Malayalam',
  hi: 'Hindi',
  ta: 'Tamil',
  te: 'Telugu',
  kn: 'Kannada',
  bn: 'Bengali',
  es: 'Spanish',
  fr: 'French',
  de: 'German',
  pt: 'Portuguese',
  ar: 'Arabic',
  ja: 'Japanese',
  ko: 'Korean'
};

export function detectMayaLanguage(): MayaLanguage {
  const locale = Intl.DateTimeFormat().resolvedOptions().locale || 'en';
  const code = locale.split('-')[0].toLowerCase();
  return { code, label: LANGUAGE_LABELS[code] ?? 'English' };
}

export function languageInstruction(language: MayaLanguage): string {
  if (language.code === 'en') return 'Reply in clear, natural English.';
  return `Reply in ${language.label} (${language.code}) when you can do so reliably. If the request is technical or the language is not supported well, keep important terms in English and stay clear rather than inventing a translation.`;
}

export function translateForDisplay(text: string): string {
  // The local model is the translator when it supports the detected language.
  // This layer intentionally leaves uncertain text unchanged instead of
  // pretending that a tiny offline model can translate every language.
  return text.trim();
}