# PostgreSQL local do catálogo

Esta etapa prepara somente o banco de desenvolvimento. A integração do Catalog Service com PostgreSQL será uma etapa separada.

Execute os comandos abaixo na raiz do repositório, com Docker e Docker Compose v2 instalados e o Docker em execução:

```powershell
docker version
docker info
docker compose version
if (-not (Test-Path .env)) { Copy-Item .env.example .env }
```

O `.env.example` contém valores fictícios para uso local. O `.env` é ignorado pelo Git. Configure `CATALOG_DB_USERNAME`, `CATALOG_DB_PASSWORD` e `CATALOG_DB_PORT` antes de iniciar o banco. Use entradas simples `CHAVE=valor`, sem aspas ou `export`.

A porta padrão é 5432. Se já estiver ocupada, escolha outra em `CATALOG_DB_PORT` e ajuste também a porta em `CATALOG_DB_URL`, que será usada na futura integração com a aplicação. Não interrompa um banco existente para liberar a porta.

```powershell
docker compose up -d --wait catalog-db
docker compose ps
```

O Compose inicia somente PostgreSQL 17, com banco `catalog`, porta publicada em `127.0.0.1`, volume nomeado `catalog_postgres_data` e health check com `pg_isready`. Não cria tabelas de negócio nesta etapa.

Para parar preservando os dados:

```powershell
docker compose stop catalog-db
```

Não remova volumes nem dados locais. Alterar as credenciais no `.env` não altera as de um volume já inicializado; mantenha a configuração correspondente ao banco existente.
