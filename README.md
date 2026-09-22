# Sinal de Fumaça — app Android seguindo o padrão MPI

Dois celulares, cada um com uma montanha, o céu e uma fogueira. Quando você toca na
fogueira, sobe um sinal de fumaça no topo da montanha **do outro celular**, e vice-versa.
A comunicação segue o padrão *MPI: A Message-Passing Interface Standard, versão 5.0*
(o PDF deste trabalho).

## Como usar

1. Instale o `SinalDeFumaca.apk` (fica na pasta acima desta) nos dois celulares.
2. **Celular A**: ligue o hotspot (roteador Wi-Fi) e abra o app → **Acender fogueira (servidor)**.
   A tela mostra o `port_name`, por exemplo `192.168.43.1:50999`.
3. **Celular B**: conecte ao hotspot do A, abra o app → **Conectar (cliente)**.
   O campo já vem preenchido com o IP do hotspot; se não vier, digite o `port_name` do A.
4. Toque na fogueira de qualquer um dos celulares: a fumaça aparece no outro.
5. Voltar (botão "voltar" do Android) desconecta os dois.

## Como o MPI é usado

Os dois celulares são processos MPI iniciados de forma independente (inicialização
*singleton*, Seção 11.10.2). Sem um `mpiexec` para ligá-los, a conexão é feita pelo
modelo cliente/servidor da **Seção 11.9 (Establishing Communication)**, que devolve um
**inter-comunicador**. Depois disso tudo é comunicação ponto-a-ponto (Capítulo 3).

| Momento no app                  | Chamada MPI                                                   | Seção do padrão |
|---------------------------------|---------------------------------------------------------------|-----------------|
| App abre                        | `MPI_Init_thread(MPI_THREAD_MULTIPLE)`                        | 11.2, 11.6      |
| Celular do hotspot              | `MPI_Open_port` → mostra o `port_name` → `MPI_Comm_accept`    | 11.9.1, 11.9.2  |
| Celular conectado ao hotspot    | `MPI_Comm_connect(port_name)`                                 | 11.9.3          |
| Toque na fogueira               | `MPI_Send(buf={3 nuvens, nº}, 2, MPI_INT, dest=0, tag=1, intercomm)` | 3.2.1    |
| Espera por sinais               | `MPI_Irecv(..., MPI_ANY_SOURCE, MPI_ANY_TAG, intercomm)` + `MPI_Wait` | 3.7.2, 3.7.3 |
| Ler o sinal recebido            | `status.MPI_TAG`, `status.MPI_SOURCE`, `MPI_Get_count`        | 3.2.5           |
| Sair                            | `MPI_Send(tag=2 "adeus")`, `MPI_Cancel`, `MPI_Comm_disconnect`, `MPI_Close_port`, `MPI_Finalize` | 3.8.4, 11.9, 11.2.2 |

Tags (Seção 3.2.3): `TAG_FUMACA = 1` (sinal de fumaça) e `TAG_ADEUS = 2` (o outro saiu).

### A implementação MPI (`app/src/main/java/br/mpi/fumaca/mpi/`)

Não existe uma biblioteca MPI pronta para apps Android (MPICH/Open MPI precisam de
`mpiexec`/ssh), então o app traz uma implementação própria, pequena, em Kotlin, com a
mesma API e a mesma semântica do padrão:

- **Envelope da mensagem** (Seção 3.2.3): cada mensagem leva `source`, `dest`, `tag` e o
  contexto do comunicador num cabeçalho de tamanho fixo sobre TCP, como sugere o
  *Advice to implementors* dessa seção.
- **Casamento de mensagens** (Seção 3.2.4): um receive casa por `source`, `tag` e
  comunicador, com os curingas `MPI_ANY_SOURCE` e `MPI_ANY_TAG`.
- **Ordem de chegada** (Seção 3.5): mensagens nunca se ultrapassam (fila de mensagens
  inesperadas e fila de receives postados, sempre em ordem).
- **Tipos de dados** (Seção 3.2.2): `MPI_INT`, `MPI_CHAR`, `MPI_BYTE`; o `count` é em
  elementos, não em bytes; há verificação de tipo e erro `MPI_ERR_TRUNCATE`.
- **Não-bloqueante** (Seção 3.7): `MPI_Isend`, `MPI_Irecv`, `MPI_Wait`, `MPI_Test`;
  também `MPI_Probe`/`MPI_Iprobe` (3.8.1), `MPI_Cancel` (3.8.4) e `MPI_PROC_NULL` (3.10).
- **Erros** (Seção 9.3): funcionam como `MPI_ERRORS_RETURN`, na forma de
  `MpiException` com a classe do erro (`MPI_ERR_PORT`, `MPI_ERR_TAG`, `MPI_ERR_RANK`...).

## Compilar e testar

```
gradlew assembleDebug        # gera app/build/outputs/apk/debug/app-debug.apk
gradlew testDebugUnitTest    # testes da camada MPI (dois processos via localhost)
```
