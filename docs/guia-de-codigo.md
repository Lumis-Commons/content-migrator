# Guia de código do módulo

Para escolher papéis de pacote, nomes de tipos, DTOs, records, enums e critérios de compartilhamento em commons, consulte as [diretrizes de código](DIRETRIZES-CODIGO.md). Este guia permanece a referência de estilo: TAB com largura 4, chaves Allman e limite de 170 colunas.

Este guia descreve como o código Java e XML do serviço `br.com.lumis.commons.contentmigrator.service.contentmigration` está organizado e escrito, para que quem abre uma classe encontre o fluxo de cima para baixo, sem decifrar formatação. Cada regra traz um exemplo curto de antes e depois, tirado do próprio módulo, e a ferramenta que a verifica. As regras são as mesmas que os checkers aplicam; quando o texto e a ferramenta divergem, vale a ferramenta.

No texto, `R` é o pacote raiz `br.com.lumis.commons.contentmigrator.service.contentmigration`, em `src/br/com/lumis/commons/contentmigrator/service/contentmigration`.

## Organização de pacotes

Cada pacote tem uma responsabilidade, e as dependências seguem uma direção única, sem ciclos. Os XML de definição do Lumis (`servicedefinition.xml`, `douidefinition.xml`, `componentdefinition.xml`, `strings/` e `componentfiles/setup/`) ficam onde estão.

| Pacote | Papel |
| --- | --- |
| `R.api` | `MigrationApi`, fachada estática chamada por reflexão pela UI, pelo REST e pelos testes. Nome, métodos e chaves de `Map` não mudam. |
| `R.dataprovider`, `R.processaction`, `R.rest` | Adaptadores do Lumis: seis `*DataProvider`, dois `*ProcessActionHandler` e `MigrationStatusResource`. O `douidefinition.xml` aponta para esses nomes completos. |
| `R.query` | Consultas de leitura da administração e do REST: `MigrationAdminQueries`, `WidgetMappingQueries` e `MigrationStatusQueries`. |
| `R.execution`, `R.config` | `MigrationExecutor`, `MigrationTask` e `RunStatusUpdater`; `ExecutionSettings` com as propriedades de sistema. |
| `R.lock`, `R.security` | `GlobalLock` (uma migração por vez) e `MigrationPermissions`. |
| `R.copy`, `R.undo`, `R.validation` | Cópia de um `ContentLocale` (`ItemProcessor`, `ContentCopier`, `WorkflowCloner` e `ItemDependencies`, com os handlers e contextos do item), desfazer (`MigrationUndoer`) e `DependencyRules`. |
| `R.dependency` | SPI `DependencyHandler`, `DependencyContext`, `DependencyHandlerRegistry`, `RepositoryCloner`, `FileCloner` e os subpacotes `media`, `document`, `categorization` e `widget`. |
| `R.dao`, `R.dto`, `R.exception` | `Transactions` e um DAO por tabela `lum_cmig_*`; linhas e valores tipados, inclusive os DTOs `MigrationScope` e `VersionCopy`; exceções do módulo. |

A direção das dependências é esta, onde cada nó é o primeiro segmento abaixo de `R`:

- `dataprovider`, `processaction` e `rest` dependem de `api`, `query`, `dto` e `exception`.
- `api` depende de `execution`, `undo`, `validation`, `security`, `lock`, `config`, `dao`, `dto` e `exception`.
- `execution` depende de `copy`, `undo`, `dependency`, `security`, `lock`, `config`, `dao`, `dto` e `exception`.
- `copy` depende de `dependency`, `validation`, `lock`, `config`, `dao`, `dto` e `exception`; `undo`, de `copy`, `dependency`, `validation`, `lock`, `dao`, `dto` e `exception`.
- `dependency` depende de `dao`, `dto` e `exception`; `query`, de `validation`, `security`, `lock`, `dao`, `dto` e `exception`.
- `validation` depende de `dto` e `exception`; `lock`, de `dao`; `dao`, de `dto` e `exception`; `exception`, de `dto`.
- `dto`, `config` e `security` não dependem de nada do módulo.

O serviço `br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration`, em `src/br/com/lumis/commons/contentmigrator/service/hierarchicalcontentmigration`, segue o mesmo mapa com `R` igual à sua raiz e acrescenta o pacote `R.hierarchy`: `HierarchyQueries`, `HierarchyTree`, `MigrationSelection` e `HierarchyBrowser`, que leem a árvore da instância, resolvem a seleção e atendem o navegador de conteúdos. O id do serviço hierárquico fica em `R.config.HierarchicalContentService`. As diferenças de direção são estas:

- `hierarchy` depende de `config`, `dao`, `dto` e `exception`.
- `api` e `copy` dependem também de `hierarchy`.
- `rest` depende também de `hierarchy`, `security` e `config`; `dataprovider`, também de `config`.
- `security` depende de `exception`, porque `MigrationPermissions` lança `FORBIDDEN`.
- Os testes ganham `R.test.hierarchy`.

Os testes seguem o mesmo critério, por aquilo que verificam: `R.test.migration` (cópia, desfazer e ciclo de vida), `R.test.admin` (administração, mapeamento de widget e REST), `R.test.contract` (persistência, SPI e smoke do harness), `R.test.spike` e `R.test.support` (fixtures, `ModuleBridge` e limpeza verificável com `TestCleanup`).

Antes: um tipo aninhado servia a uma classe de outro papel, e o arquivo misturava registro, SPI e cópia de arquivo.

```java
// engine/MigrationEngine.java, engine/FileCloner.java
public static final class Copy { ... }
```

Depois: o tipo ganha o seu pacote e o nome do papel, como [`FileCopy`](../src/br/com/lumis/commons/contentmigrator/service/contentmigration/dto/FileCopy.java#L6-L13) em `R.dto`. Um tipo aninhado privado usado por uma só classe continua aninhado.

Como verificar: `python3 test/check_structure.py` confere o mapa e a direção das dependências dos dois serviços e falha se qualquer um falhar; `--root contentmigration` ou `--root hierarchicalcontentmigration` confere só um; `--strict-tests` exige os subpacotes de teste.

## Indentação e chaves

Indentação com TAB (largura 4) em `.java` e `.xml`, sem linha indentada com espaços e sem espaço no fim da linha. As chaves seguem o estilo Allman: `{` e `}` ficam em linha própria, alinhadas com a declaração dona (tipo, método, `if`, `else`, `for`, `while`, `try`, `catch`, `finally`, `switch`, lambda e classe anônima). Inicializador de array ou de anotação abre `{` na linha da declaração.

Toda instrução de controle (`if`, `else`, `for`, `while`, `do`) tem bloco com chaves, mesmo com uma única instrução.

Antes, em `engine/WorkflowCloner.java`:

```java
if (UserConfig.USER_SYSTEM_ID.equals(assignedTo) && workflow != null && !workflow.getRoles().isEmpty())
	return assignedTo;
```

Depois, em [`WorkflowCloner`](../src/br/com/lumis/commons/contentmigrator/service/contentmigration/copy/WorkflowCloner.java#L107-L112):

```java
if (isSystemUser && hasWorkflowRoles)
{
	return assignedTo;
}
```

Como verificar: `test/check_style.py` aponta TAB, espaço final, Allman e chaves obrigatórias; `test/format.sh` corrige tudo isso.

## Largura e quebras de linha

A largura máxima é de 170 colunas, contando o TAB como 4. Instrução que cabe em 170 colunas fica numa linha só; o formatador junta linhas já quebradas que cabem. Quando não cabe, tente extrair uma variável ou um método antes de quebrar. Se ainda precisar quebrar, quebre no nível sintático mais alto: depois da vírgula, antes do operador (`+`, `&&`, `||`, `?`, `:`) e antes do ponto da cadeia, com continuação de +2 TABs. Lista de parâmetros ou argumentos que não cabe fica com um item por linha.

Em [`MigrationTask.updateCounts`](../src/br/com/lumis/commons/contentmigrator/service/contentmigration/execution/MigrationTask.java#L239-L247), a chamada que não cabe é aberta no nível mais alto e cada argumento ocupa uma linha:

```java
boolean isLockHeld = Transactions.inShortTransaction(
		connection -> RunStatusUpdater.updateCounts(
				connection,
				migrationId,
				runId,
				totalCount,
				ItemProcessor.countMigrated(connection, originServiceInstanceId, destServiceInstanceId)));
```

Uma instrução por linha e uma variável por declaração. Variável local nasce perto do primeiro uso. Prefira retorno antecipado a `if`/`else` aninhado e não use `else` depois de `return`, `throw`, `break` ou `continue`. Método com mais de 80 linhas ou mais de 4 blocos aninhados é dividido em métodos privados cujo nome diz o passo, como `queryOriginItems`, `copyVersions` e `copyLocaleMetadata` em [`ItemProcessor`](../src/br/com/lumis/commons/contentmigrator/service/contentmigration/copy/ItemProcessor.java#L369-L413). A exceção é o mapeamento linear de colunas em DAO, marcado com `// style:long-method <motivo>` na linha anterior ao método.

Linhas em branco: uma entre membros e uma entre passos lógicos; nunca duas seguidas, nem logo após `{` ou antes de `}`.

Como verificar: `test/check_style.py` mede a largura; no conjunto completo (`--rules all`) também conta linhas e blocos aninhados do método. `test/format.sh` junta e quebra linhas.

## Condições e variáveis explicativas

A condição de `if`, `else if`, `while` e `for` termina na mesma linha da palavra-chave e tem no máximo dois termos ligados por `&&` ou `||`. Se tiver mais termos, se não couber ou se o significado não estiver claro, extraia variáveis booleanas finais com nome de regra.

Antes, em `engine/MigrationTask.java`:

```java
if (start != 0 && System.currentTimeMillis() - start > itemTimeLimitMillis)
```

Depois, em [`MigrationTask.renewHeartbeat`](../src/br/com/lumis/commons/contentmigrator/service/contentmigration/execution/MigrationTask.java#L148-L154):

```java
long startMillis = currentItemStartMillis;
boolean isItemOverTimeLimit = startMillis != 0 && System.currentTimeMillis() - startMillis > itemTimeLimitMillis;
if (isItemOverTimeLimit)
{
	...
	return;
}
```

Outro caso está em [`WorkflowCloner`](../src/br/com/lumis/commons/contentmigrator/service/contentmigration/copy/WorkflowCloner.java#L107-L112): `isSystemUser` e `hasWorkflowRoles` explicam a regra de manter o responsável.

Como verificar: `python3 test/check_style.py --rules all` aponta condição fora da linha da palavra-chave ou com mais de dois termos.

## Parâmetros, DTOs e Builder

Método ou construtor de `src` recebe no máximo 5 parâmetros. Quando a lista passa disso, procure a causa antes de quebrar a linha:

- **Grupo repetido** (sessão, transação, conexão, `serviceId`, instâncias, sources, modo de atualização, warnings): vira o DTO global [`MigrationScope`](../src/br/com/lumis/commons/contentmigrator/service/contentmigration/dto/MigrationScope.java#L18-L57), um por transação de item.
- **Dados de uma versão a gravar** (linha de origem, versão de origem e ids de destino): vira o DTO [`VersionCopy`](../src/br/com/lumis/commons/contentmigrator/service/contentmigration/dto/VersionCopy.java#L12-L47).
- **Linha que já existe**: passe a própria linha, como a `MigrationRow` que `ItemProcessor`, `MigrationTask` e `MigrationUndoer.undoRepositoryItem` recebem.
- **Valor derivável** de outro parâmetro (a conexão da transação, o `Content` do `ContentLocale`) ou **flag** que escolhe o caminho: tire o parâmetro e derive o valor, ou divida o método.

DTO fica em `R.dto`, é `final`, tem campos `final` e só getters. Com até 5 campos, o construtor recebe todos e valida os obrigatórios, como `VersionCopy`. Acima de 5 campos, o construtor é privado e o objeto nasce de um `Builder` obtido pelo método estático `builder()`; `build()` valida os campos obrigatórios com `Objects.requireNonNull` e o nome do campo. Um DTO não referencia `R.dependency` nem outro pacote do módulo: por isso `FileCloner`, handlers e contextos ficam em `ItemDependencies`, em `R.copy`.

Antes, em `copy/MetadataPolicy.java`, seis parâmetros, quatro deles repetidos em toda a cadeia de cópia:

```java
static String resolveUniqueSlug(
		Connection connection,
		String destinationServiceInstanceId,
		String sourceId,
		String requestedSlug,
		String currentDestinationContentLocaleId,
		List<String> warnings) throws SQLException
```

Depois, em [`MetadataPolicy.resolveUniqueSlug`](../src/br/com/lumis/commons/contentmigrator/service/contentmigration/copy/MetadataPolicy.java#L89-L95), o escopo traz a conexão, a instância de destino, a source e os warnings:

```java
static String resolveUniqueSlug(MigrationScope scope, String requestedSlug, String currentDestinationContentLocaleId) throws SQLException
```

O escopo é montado uma vez em [`ItemProcessor.processOnce`](../src/br/com/lumis/commons/contentmigrator/service/contentmigration/copy/ItemProcessor.java#L172-L208):

```java
MigrationScope scope = MigrationScope.builder()
		.session(session)
		.transaction(transaction)
		.connection(connection)
		...
		.updateMode(isUpdateMode())
		.build();
```

A exceção é a linha `// style:many-params <motivo>` imediatamente acima da declaração, para um contrato que não pode mudar (uma assinatura chamada por reflexão, por exemplo). Hoje nenhuma declaração de `src` usa a exceção, e os testes em `test/java` não têm limite.

Como verificar: `python3 test/check_style.py` aplica o limite de 5 em `src` sem opção extra; `--max-params N` troca o limite.

## Ordem dos membros

A classe lê de cima para baixo: constantes; campos estáticos; campos de instância; construtores; métodos públicos na ordem do fluxo de uso; cada método privado logo abaixo do primeiro método que o usa; tipos aninhados privados no fim. Sobrecargas ficam juntas.

[`ItemProcessor`](../src/br/com/lumis/commons/contentmigrator/service/contentmigration/copy/ItemProcessor.java#L77-L172) segue essa ordem: o construtor, `discover`, os privados `findContentSourceIds` e `queryOriginItems` que ele usa, depois `process` e `processOnce`. O leitor não precisa procurar de onde cada privado é chamado.

Como verificar: a ordem é revisada na leitura; o `test/format.sh` não reordena membros.

## Nomes

A classe diz o papel com o sufixo do tipo: `...DataProvider`, `...ProcessActionHandler`, `...Dao`, `...Row`, `...Exception`, `...Handler`. Método começa por verbo. Booleano começa por `is`, `has`, `can` ou `should`. Não há abreviação além de `id`, `sql`, `url`, `html`, `xml` e `dto`.

Antes, os nomes escondiam o papel ou dependiam do tipo dono:

```java
ItemProcessor.Item
ItemProcessor.Outcome
MigrationStatusService
```

Depois, cada tipo diz o que é, em seu pacote: `R.dto.MigrationItem`, `R.dto.ItemOutcome` e [`MigrationStatusQueries`](../src/br/com/lumis/commons/contentmigrator/service/contentmigration/query/MigrationStatusQueries.java), em `R.query`. Os booleanos `isLockHeld`, `hasPublishedPair` e `isSameTextAndAuthor` seguem a mesma regra.

Como verificar: `python3 test/check_structure.py` confere os nomes e pacotes do mapa; o restante é revisado na leitura.

## Comentários e Javadoc

Comentários e Javadoc ficam em inglês, como no código Lumis. Toda classe e todo método não privado têm Javadoc cujo primeiro parágrafo diz o que faz e por quê, sem repetir a assinatura. Comentário de linha explica o porquê, nunca o quê. A prosa quebra em até 120 colunas.

Em [`WorkflowCloner`](../src/br/com/lumis/commons/contentmigrator/service/contentmigration/copy/WorkflowCloner.java#L36-L40), o Javadoc diz a razão do método, não o seu nome:

```java
/**
 * Places a destination locale in its workflow start state before an update or deletion so the
 * native source allows modifying or removing archived or published content.
 */
```

Como verificar: o texto dos comentários é revisado na leitura; o checker ignora a prosa.

## SQL e XML

SQL em `String` usa uma cláusula por linha (`select`, `from`, `join`, `where`, `and`, `order by`), entre `// @formatter:off` e `// @formatter:on`, para o formatador não juntar as linhas. Em [`ItemProcessor.queryOriginItems`](../src/br/com/lumis/commons/contentmigrator/service/contentmigration/copy/ItemProcessor.java#L122-L131):

```java
// @formatter:off
String sql = "select cl.id, c.id, c.sourceId, cl.locale "
		+ "from lum_ContentLocale cl "
		+ "inner join lum_Content c on c.id = cl.contentId "
		+ "where c.serviceInstanceId = ? "
		+ "order by case when cl.locale = c.defaultLocale then 0 else 1 end, cl.createdDateTime, cl.id";
// @formatter:on
```

Imports sem curinga e sem import não usado, na ordem do Organize Imports padrão do Eclipse (`java`, `javax`, `org`, `com` e depois os demais em ordem alfabética), com grupos separados por linha em branco.

No XML (`douidefinition`, `servicedefinition`, `strings`, `setup`), a indentação é com TAB, um elemento por linha e atributos na ordem existente. Um comentário `<!-- ... -->` abre cada grupo (fontes, interfaces, controles, ações de processo), como no [`douidefinition.xml`](../src/br/com/lumis/commons/contentmigrator/service/contentmigration/douidefinition.xml#L195-L204). Ids, nomes, textos e ordem dos elementos não mudam.

Como verificar: `test/check_style.py` confere TAB, espaço final e largura também no XML.

## Ferramentas

Os dois scripts de estilo são a fonte de verdade do layout; rode-os a partir da raiz do módulo.

- `bash test/format.sh [CAMINHO ...]` aplica o OpenRewrite `NeedBraces` e o formatador Eclipse com `config/eclipse-formatter.xml`. Sem argumentos, formata `src` e `test/java`. Só Java; o XML é ajustado à mão.
- `python3 test/check_style.py [--rules mechanical|all] [--max-params N] [CAMINHO ...]` verifica o código sem alterá-lo. `mechanical` confere TAB, espaço final, Allman, chaves obrigatórias e largura. `all` confere ainda condição numa linha com até dois termos, método até 80 linhas e até 4 blocos aninhados. Nos dois modos, declaração de `src` com mais de 5 parâmetros falha, salvo `// style:many-params <motivo>` na linha anterior; `--max-params N` troca o limite e vale também fora de `src`.
- `python3 test/check_structure.py [--root SERVIÇO] [--strict-tests]` confere o mapa de pacotes e a direção das dependências; sem `--root`, confere os dois serviços.
- `python3 test/check_architecture.py --profile guide docs/guia-de-codigo.md` confere este guia: seções, referências e links.
- `python3 test/check_bytecode.py compare --base REF` compara o bytecode do `REF` com o da cópia de trabalho de uma mudança de formato, para provar que só o layout mudou.

Fluxo recomendado: edite, rode `format.sh`, rode `check_style.py --rules all` e `check_structure.py`, e só então os testes de integração com `bash test/run-tests.sh --all`, que publica o módulo e usa uma JVM Portal própria.
