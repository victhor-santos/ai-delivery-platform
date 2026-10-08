// Valores em reais chegam como números JSON com até duas casas; as contas são feitas em centavos inteiros.

const formatter = new Intl.NumberFormat('pt-BR', { style: 'currency', currency: 'BRL' })

export function toCents(amount: number): number {
  return Math.round(amount * 100)
}

export function formatCents(cents: number): string {
  return formatter.format(cents / 100)
}

export function formatAmount(amount: number): string {
  return formatCents(toCents(amount))
}
