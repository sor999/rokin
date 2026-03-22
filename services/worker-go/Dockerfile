FROM golang:1.22-bookworm AS builder

RUN apt-get update && apt-get install -y librdkafka-dev && rm -rf /var/lib/apt/lists/*

WORKDIR /app

COPY go.mod ./
COPY . .

RUN go mod tidy && CGO_ENABLED=1 go build -o worker ./cmd/worker

FROM debian:bookworm-slim

RUN apt-get update && apt-get install -y librdkafka1 ca-certificates && rm -rf /var/lib/apt/lists/*

WORKDIR /app

COPY --from=builder /app/worker .

ENTRYPOINT ["./worker"]
