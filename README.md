# Rate Proxy — Proxy Interno com Rate Limiting

Serviço proxy resiliente desenvolvido em Java com Spring Boot para consumir a API
`score.hsborges.dev` respeitando o limite de 1 requisição por segundo.

## Decisões de Design

### Padrões Utilizados
- **Proxy** — o serviço intercepta todas as chamadas internas e as repassa ao upstream,
  abstraindo o `client-id` dos clientes internos.
- **Queue/Buffer** — fila com prioridade (`PriorityBlockingQueue`) para absorver rajadas
  de requisições sem violar o rate limit externo.
- **Scheduler** — timer fixo de 1 segundo (`ScheduledExecutorService`) que controla
  o ritmo de envio ao upstream.
- **Cache** — respostas são armazenadas em memória (`ConcurrentHashMap`) evitando
  chamadas repetidas ao upstream para o mesmo CPF.
- **Shed Load** — quando a fila atinge o limite máximo, novas requisições são descartadas
  com mensagem amigável.
- **Adaptive Rate Control** — ao detectar resposta 429, o proxy pausa automaticamente
  por 3 segundos e recoloca a requisição na fila.

### Padrões Rejeitados
- **Circuit Breaker completo (Resilience4j)** — optei por controle adaptativo simples
  pois o comportamento da API parceira é previsível (429 + tempo de espera fixo).
- **Redis para cache** — desnecessário para o escopo do projeto; cache em memória
  é suficiente e elimina dependência externa.

## Como Rodar

### Pré-requisitos
- Java 17+
- Maven 3.8+

### Variáveis de Ambiente

| Variável | Descrição | Padrão |
|---|---|---|
| `CLIENT_ID` | Client ID fornecido pela API parceira | `client-id` |

### Rodando localmente

```bash
# Clone o repositório
git clone https://github.com/SEU_USUARIO/rate-proxy.git
cd rate-proxy

# Rode o projeto
./mvnw spring-boot:run
```

O servidor sobe na porta `8080`.

## Endpoints

### `GET /proxy/score`
Consulta o score de um CPF via proxy.

**Parâmetros:**
| Parâmetro | Tipo | Descrição |
|---|---|---|
| `cpf` | query | CPF a ser consultado |
| `X-Priority` | header | Prioridade na fila (padrão: 1) |
| `X-TTL-Seconds` | header | Tempo máximo de espera na fila (padrão: 30s) |

**Exemplo:**
```bash
curl "http://localhost:8080/proxy/score?cpf=529.982.247-25"
```

**Resposta:**
```json
{
  "cpf": "529.982.247-25",
  "score": 308,
  "message": "O score de 529.982.247-25 é 308"
}
```

---

### `GET /proxy/health`
Verifica se o serviço está no ar.

```bash
curl http://localhost:8080/proxy/health
```

**Resposta:**
```json
{"status": "UP"}
```

---

### `GET /metrics`
Retorna métricas de uso em tempo real.

```bash
curl http://localhost:8080/metrics
```

**Resposta:**
```json
{
  "total_requests": 20,
  "cache_hits": 15,
  "upstream_calls": 5,
  "penalties_detected": 1,
  "dropped_requests": 0,
  "current_queue_size": 0
}
```

## Cenários de Teste

### Teste 1 — Rajada de requisições
```powershell
$jobs = 1..20 | ForEach-Object {
    Start-Job -ScriptBlock {
        Invoke-RestMethod "http://localhost:8080/proxy/score?cpf=529.982.247-25"
    }
}
$jobs | Wait-Job | Receive-Job
```
**Esperado:** upstream recebe ~1 req/s, demais retornam do cache.

### Teste 2 — Requisição prioritária
```bash
curl -H "X-Priority: 10" "http://localhost:8080/proxy/score?cpf=529.982.247-25"
```
**Esperado:** requisição passa à frente na fila.

### Teste 3 — Verificar métricas
```bash
curl http://localhost:8080/metrics
```
**Esperado:** contadores refletem todas as operações realizadas.

## Análise Crítica — Trade-offs

| Decisão | Vantagem | Desvantagem |
|---|---|---|
| Cache em memória | Simples, sem dependências | Perdido ao reiniciar |
| Fila em memória | Alta performance | Perdido ao reiniciar |
| Scheduler fixo 1s | Garante rate limit | Não aproveita janelas livres |
| Shed load | Protege o sistema | Cliente recebe erro imediato |