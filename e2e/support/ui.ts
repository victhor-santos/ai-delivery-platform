import { expect, type Page } from '@playwright/test'
import type { Account } from './api.ts'

export async function signIn(page: Page, account: Account): Promise<void> {
  await page.goto('/login')
  await page.getByLabel('E-mail').fill(account.email)
  await page.getByLabel('Senha').fill(account.password)
  await page.getByRole('button', { name: 'Entrar' }).click()
  await expect(page.getByRole('heading', { name: /^Olá,/ })).toBeVisible()
}

export async function signUp(page: Page, name: string, account: Account): Promise<void> {
  await page.goto('/register')
  await page.getByLabel('Nome').fill(name)
  await page.getByLabel('E-mail').fill(account.email)
  await page.getByLabel('Senha').fill(account.password)
  await page.getByRole('button', { name: 'Criar conta' }).click()
  await expect(page.getByRole('heading', { name: `Olá, ${name}` })).toBeVisible()
}

export function brl(value: string): RegExp {
  return new RegExp(`R\\$\\s${value.replace('.', '\\.')}`)
}

export function orderIdFrom(page: Page): string {
  const match = /\/orders\/([^/?#]+)/.exec(page.url())
  if (!match) {
    throw new Error(`Not on an order page: ${page.url()}`)
  }
  return match[1]
}
