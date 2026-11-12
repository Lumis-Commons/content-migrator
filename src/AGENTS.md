# src — código do produto (AGENTS)

Tudo nesta pasta é produto. Não coloque testes, fixtures, mocks, ponte de teste ou segredos aqui.
O pré-deploy copia os recursos de `src/` para `target/classes`, excluindo Java e `.DS_Store`; não presuma outras exclusões.

## Organização

- Serviços ficam em `br/com/lumis/commons/contentmigrator/service/<serviço>/`, com subpacotes por responsabilidade; preserve as fronteiras existentes.
- Adaptadores do Portal delegam regras ao núcleo; não leve dependências REST, DOUI ou de UI para DTOs e regras de cópia.
- Reorganizar código exige atualizar os testes correspondentes em `test/java`, imports e nomes de classes em texto no mesmo trabalho.
- Mover ou remover arquivos não remove serviços, componentes ou dados já instalados no Portal.
- AGENTS.md de serviço contém só regras próprias que o código não mostra, até sete linhas; sua rota fica na tabela abaixo, pelo caminho completo.

## Rotas

| Quando a tarefa… | Leia |
|---|---|
| cria classe, pacote, DTO, record ou enum | `docs/DIRETRIZES-CODIGO.md` |
| altera instruções ou documentação de AGENTS.md | `docs/GUIA-AGENTS-MD.md` |
| muda decisão ou contrato de migração | `docs/DECISOES-TECNICAS.md`, seção do assunto |
| precisa sincronizar ou remover estrutura instalada | `docs/OPERAR-MODULO-NO-PORTAL.md` |

## Serviços

Antes de editar um serviço, leia suas regras próprias.

| Serviço | Propósito | Regras próprias |
|---|---|---|
| `contentmigration` | Migração integral entre instâncias do mesmo serviço de conteúdo. | `src/br/com/lumis/commons/contentmigrator/service/contentmigration/AGENTS.md` |
| `hierarchicalcontentmigration` | Migração de conteúdos hierárquicos selecionados e seus descendentes. | `src/br/com/lumis/commons/contentmigrator/service/hierarchicalcontentmigration/AGENTS.md` |

## Strings

- Texto visível vem de `service/<serviço>/strings/strings.<locale>.xml`, com ids `STR_*`; mantenha ids equivalentes nos idiomas oferecidos.
- Use a localização Lumis no idioma da sessão nas bordas Java, REST e estilos; preserve os códigos usados como contrato.
- Não traduza mensagens fixando texto no JS nem transforme detalhes de exceção em mensagens de interface.

## Build local

- Para compilar, limpar classes locais ou publicar, siga `docs/OPERAR-MODULO-NO-PORTAL.md`, seção “Compilar e publicar” (decisão CM-D006).
