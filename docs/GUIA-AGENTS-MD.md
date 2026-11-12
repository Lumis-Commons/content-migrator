# Guia dos arquivos AGENTS.md

Critérios para manter as instruções do Content Migrator curtas e verificáveis. A organização documental está registrada na decisão CM-D005 de `docs/DECISOES-TECNICAS.md`.

## Critério de inclusão

Uma regra entra no AGENTS.md de uma pasta quando as três respostas são positivas:

1. Sua ausência pode levar a uma alteração incompatível com o módulo?
2. A informação não fica evidente ao ler o código pertinente?
3. A regra vale para toda a pasta ou para mais de um serviço?

Se apenas a terceira resposta for negativa, a regra pertence ao AGENTS.md do serviço. Detalhes de implementação, inventários de classes, tabelas e endpoints ficam no código ou no documento especializado. O arquivo global contém a rota para esse conteúdo.

## Limites

Contar todas as linhas físicas, inclusive títulos e linhas vazias, sem descontar tabelas.

| Arquivo ou campo | Máximo |
|---|---:|
| `AGENTS.md` da raiz | 45 linhas |
| `src/AGENTS.md` | 95 linhas |
| `test/AGENTS.md` | 80 linhas |
| AGENTS.md de serviço | 7 linhas |
| Propósito na tabela de serviços de `src/AGENTS.md` | 120 caracteres |

Ao exceder um limite, retirar primeiro fatos visíveis no código e regras restritas a um serviço; depois condensar a redação. Não criar um novo arquivo global só para repartir o mesmo conteúdo.

## Onde vai cada informação

| Informação | Destino |
|---|---|
| Regra transversal de trabalho | AGENTS.md da pasta afetada |
| Regra exclusiva de serviço | AGENTS.md desse serviço |
| Contrato, critério de teste, cobertura ou limitação de classe | Javadoc do elemento |
| Convenção para classes, pacotes e tipos de dados | `docs/DIRETRIZES-CODIGO.md` |
| Decisão com contexto, escolha e evidência | `docs/DECISOES-TECNICAS.md`, próximo ID CM-D livre |
| Instalação, atualização, remoção e limpeza | `docs/OPERAR-MODULO-NO-PORTAL.md` |
| Levantamento técnico que fundamenta uma decisão | Estudo especializado em `docs/internal/` |
| Histórico de alterações | git |
| Credencial ou segredo | Configuração externa ao git, nunca documentação ou log |

Cada fato tem um lugar principal. Os demais documentos citam o caminho, a seção ou o ID da decisão em vez de repetir o conteúdo. Estudos preservam a evidência de sua data; uma conclusão posterior fica identificada como decisão, sem apresentar hipótese antiga como comportamento atual.

## Rotas

- A raiz roteia `src/AGENTS.md`, `test/AGENTS.md` e os guias de documentação.
- `src/AGENTS.md` roteia cada AGENTS.md de serviço por seu caminho completo a partir da raiz do módulo.
- Raiz, `src/AGENTS.md` e `test/AGENTS.md` mantêm rotas para este guia, decisões e operação conforme o tipo da tarefa.
- Um filho não se auto-roteia nem aponta de volta ao pai. A condição para abri-lo fica no pai.
- Criar ou mover documento exige ajustar sua rota no mesmo trabalho. Não criar AGENTS.md vazio ou que apenas repete o pai.

## Checklist de revisão

- [ ] Cada regra nova atende ao critério de inclusão.
- [ ] Detalhes de serviço foram colocados no serviço ou no código correspondente.
- [ ] Os limites de linhas e de propósito foram respeitados.
- [ ] Documentos e filhos têm rota no pai; não há auto-rota.
- [ ] Cada decisão tem ID único, contexto, escolha e evidência localizável.
- [ ] Referências por ID e caminho existem; nenhum fato operacional foi duplicado.
- [ ] Texto em português, UTF-8 e LF, sem segredos.
- [ ] Executados `bash test/run-static-checks.sh` e `python3 test/check_agents_md.py`; o segundo confere limites, rotas, propósitos e vocabulário de serviço nos globais, derivado dos contratos literais em `src/`.
