# Content Migrator (AGENTS)

Módulo LumisXP `br.com.lumis.commons.contentmigrator`, com git próprio. Caminhos e comandos partem desta raiz.
Antes de editar, leia o `LUMIS-AI-INSTRUCTIONS.md` do pacote de referência de IA do LumisXP, em `../../data/lumis-ai-reference/` quando o módulo fica em `lumisdata/shared/source/`.

## Pastas

| Pasta | O que é |
|---|---|
| `src/` | Produto: dois serviços de migração, coleção de componentes, estrutura e tema. |
| `test/` | Testes Java, fixtures, harness do Portal, verificadores estáticos e cenários de UI. |
| `docs/` | Guias, decisões, arquitetura e evidências técnicas. |
| `config/` | Configuração auxiliar do módulo. |
| `pom.xml`, `predeploy-ant.xml` | Compilação Maven e cópia de recursos para publicação. |

## Rotas

Leia a linha que corresponde à tarefa; abra só a seção pertinente quando houver.

| Quando a tarefa… | Leia |
|---|---|
| altera Java, serviço, REST, strings, estrutura ou tema em `src/` | `src/AGENTS.md` |
| cria, altera ou executa teste, fixture ou harness | `test/AGENTS.md` |
| cria classes, pacotes, DTOs, records ou enums | `docs/DIRETRIZES-CODIGO.md` |
| altera instruções ou documentação de AGENTS.md | `docs/GUIA-AGENTS-MD.md` |
| altera decisão, contrato ou limite de arquitetura | `docs/DECISOES-TECNICAS.md`, seção do assunto |
| publica, instala, atualiza ou remove o módulo no Portal | `docs/OPERAR-MODULO-NO-PORTAL.md` |

## Regras de qualquer tarefa

- Ambiente 19.0.0: Java 21, Tomcat 11 e Jakarta EE (`jakarta.*`); Portal em `http://localhost:8080/`, na raiz.
- `src/` vai para produção: testes, fixtures, mocks e ponte de teste ficam em `test/`.
- Ao mover ou renomear código, atualize testes, nomes de classes em texto e referências no mesmo trabalho; remova os `.class` antigos.
- Texto visível ao usuário sai das strings Lumis do serviço; não fixe mensagens em Java, JS ou estilos.
- Javadoc em inglês com `@since %LUM_NEXT_VERSION%` em elemento novo ou alterado; preserve o `@since` dos demais e não use `@author`.
- Documentos e interface em português; UTF-8 e LF, com exceções de EOL em `.gitattributes` e `.editorconfig`.
- Segredos ficam fora do git, de documentos e de logs; não copie credenciais do ambiente para o módulo.
- Testes com Portal usam o harness de `test/`, com JVM própria e exclusividade do banco; não execute JUnit em paralelo com o Tomcat.
- Publicação e ciclo de servidores pertencem ao ambiente: leia o roteador `README.md` das ferramentas `ai_tools` (pasta definida no guia de operação) e esse guia antes de agir.
- A atualização pelo `ai_tools` usa o id `br.com.lumis.commons.contentmigrator`; compilar sozinho não atualiza o módulo instalado.
