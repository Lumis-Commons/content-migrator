# Diretrizes de código do Content Migrator

Regras para escolher o papel, o pacote e o nome de classes, DTOs, records e enums do módulo. A base de produção é `src/br/com/lumis/commons/contentmigrator/`; os serviços são `contentmigration` e `hierarchicalcontentmigration`, sob `service/`. Testes ficam em `test/java`, no pacote do serviço correspondente.

Estas diretrizes adaptam o modelo de diretrizes de outro módulo LumisXP aos papéis e contratos existentes no Content Migrator. Valem para código novo e para o código que a tarefa precisa alterar; não autorizam reorganizar outros serviços. O [guia de código](guia-de-codigo.md) continua sendo a referência de estilo: TAB com largura 4, chaves Allman, limite de 170 colunas, ordem dos membros, parâmetros e Builder.

## 1. Papéis de pacote dentro de um serviço

Na tabela, os nomes de pacote são relativos à raiz de cada serviço. As classes citadas existem nos dois serviços, exceto as identificadas como hierárquicas.

| Pacote | Responsabilidade e exemplos reais |
|---|---|
| `api` | Fachada transacional de comandos: `MigrationApi`. Preserve assinaturas públicas, nomes chamados por reflexão e chaves dos mapas de entrada e saída. |
| `dataprovider`, `processaction`, `rest` | Adaptadores do Portal: `MigrationAdminDataProvider`, `MigrationProcessActionHandler` e `MigrationStatusResource`. Recebem a chamada, localizam textos e delegam ao núcleo. |
| `query` | Projeções de leitura para administração e REST: `MigrationAdminQueries`, `MigrationStatusQueries` e `WidgetMappingQueries`. |
| `execution` | Execução em segundo plano e estado da execução: `MigrationExecutor`, `MigrationTask` e `RunStatusUpdater`. |
| `copy`, `undo`, `validation` | Processamento e cópia de conteúdo (`ItemProcessor`, `ContentCopier`, `MetadataPolicy`), desfazer (`MigrationUndoer`) e regras (`DependencyRules`). |
| `dependency` e seus subpacotes | SPI e resolução das dependências de repositório: `DependencyHandler`, `DependencyContext`, `DependencyHandlerRegistry`, `FileCloner` e os handlers por tipo. |
| `dao` | SQL e mapeamento das tabelas: `MigrationDao`, `ItemMapDao` e `Transactions`. No hierárquico, `ContentMigrationCoexistenceDao` consulta as tabelas opcionais do serviço integral. |
| `dto` | Dados tipados entre papéis: `MigrationItem`, `MigrationRow`, `MigrationScope`, `VersionCopy` e o resultado `ItemOutcome`. O nome do pacote não torna todos os tipos DTOs puros. |
| `exception` | Falhas e códigos do serviço: `MigrationApiException`, `MigrationBlockedException`, `RulesViolatedException` e `StatusAccessDeniedException`. |
| `config`, `lock`, `security` | Configuração (`ExecutionSettings`), exclusividade (`GlobalLock`) e permissões (`MigrationPermissions`). |
| `hierarchy` — somente hierárquico | Leitura, seleção e navegação da árvore: `HierarchyQueries`, `HierarchyTree`, `MigrationSelection` e `HierarchyBrowser`. O dado `HierarchyNode` fica em `dto`. |

Adaptadores dependem das fachadas, consultas e dados; DTOs não importam adaptadores nem regras de cópia. Preserve a direção específica de cada serviço descrita na seção Organização de pacotes do [guia de código](guia-de-codigo.md) e verificada por `test/check_structure.py`. Não introduza uma camada de manager ou um cliente REST sem necessidade do serviço.

Crie subpacote por responsabilidade, não por quantidade de arquivos. Um tipo usado só por uma classe pode ficar aninhado e privado; não aumente sua visibilidade apenas para separá-lo em outro pacote. Enums seguem a seção 5.

## 2. Nomes e sufixos

O nome descreve domínio e papel; nomes simples iguais nos dois serviços são qualificados pelo pacote. Não acrescente prefixos de outro projeto nem renomeie classes existentes só para uniformizar a tabela.

| Papel | Convenção adotada e exemplo |
|---|---|
| Fachada de comandos | Sufixo `Api`: `MigrationApi`. |
| Consulta de leitura | Sufixo `Queries`: `MigrationStatusQueries`. |
| Persistência e linha | Sufixos `Dao` e `Row`: `MigrationDao` e `MigrationRow`. |
| Recurso JAX-RS | Sufixo `Resource`: `MigrationStatusResource` e, no hierárquico, `HierarchicalContentBrowserResource`. |
| Adaptador DOUI | Sufixos `DataProvider` e `ProcessActionHandler`: `MigrationAdminDataProvider` e `MigrationProcessActionHandler`. |
| Execução, processamento e cópia | O papel completa o domínio: `MigrationExecutor`, `MigrationTask`, `ItemProcessor`, `ContentCopier`, `AuthorshipCloner`. |
| Configuração e política | Nome do assunto com `Settings` ou `Policy`: `ExecutionSettings`, `MetadataPolicy` e, no hierárquico, `LinkFieldPolicy`. |
| Falha | Sufixo `Exception` com causa ou contexto: `MigrationBlockedException`. |
| Interface | Nome do contrato, sem prefixo `I` para interfaces novas do módulo: `DependencyHandler`. Interfaces do Portal mantêm seus nomes. |
| Dados e resultado | Nome do domínio e do papel, sem sufixo `DTO` obrigatório: `MigrationItem`, `RepositoryPair`, `VersionCopy`, `ItemOutcome`. |

Métodos começam por verbo; booleanos, por `is`, `has`, `can` ou `should`. Evite nomes vagos como resultado ou estado sem o assunto que os qualifica. Preserve os nomes usados nos XML do Lumis e por reflexão.

## 3. DTOs, objetos de valor, resultados e contexto

Escolha pelo uso do tipo, antes de escolher entre classe e record.

| Papel | Critério | Exemplo atual |
|---|---|---|
| DTO | Transporta dados entre papéis ou por uma fronteira, sem executar regra de negócio. | [MigrationItem](../src/br/com/lumis/commons/contentmigrator/service/contentmigration/dto/MigrationItem.java) identifica o conteúdo e o locale; [HierarchyNode](../src/br/com/lumis/commons/contentmigrator/service/hierarchicalcontentmigration/dto/HierarchyNode.java) leva a projeção lida para a árvore. |
| Linha de persistência | Representa as colunas que o DAO lê e grava; pode precisar de setters. | [MigrationRow](../src/br/com/lumis/commons/contentmigrator/service/contentmigration/dto/MigrationRow.java) é mutável e representa uma migração. |
| Objeto de valor | Expressa uma combinação válida de dados e suas invariantes, sem executar a operação. | [VersionCopy](../src/br/com/lumis/commons/contentmigrator/service/contentmigration/dto/VersionCopy.java) valida a presença conjunta dos ids de locale e item de destino. |
| Resultado | Identifica o desfecho de uma operação. | [ItemOutcome](../src/br/com/lumis/commons/contentmigrator/service/contentmigration/dto/ItemOutcome.java) distingue item ignorado, copiado e perda de lock. |
| Contexto de execução | Carrega recursos de uma unidade de trabalho e o estado que ela compartilha. | [MigrationScope](../src/br/com/lumis/commons/contentmigrator/service/contentmigration/dto/MigrationScope.java) reúne sessão, transação, conexão, sources e warnings de um item. |

Dados compartilhados pelos papéis existentes ficam em `dto` do serviço dono; não crie dependência desse pacote para `copy`, `dependency`, REST ou DOUI. Tipos de uso exclusivamente interno ficam com seu dono. Uma regra de cópia que apareça num DTO vai para o papel responsável; validação estrutural dos dados pode ficar no construtor.

Para uma classe de dados nova e imutável, use classe `final`, campos `final` e getters. Até cinco campos, o construtor recebe os dados e valida os obrigatórios. Acima disso, use construtor privado e `builder()`, com validação em `build()`, conforme o guia de estilo. Linhas mutáveis de persistência mantêm o contrato exigido pelo DAO.

Não confunda campo `final` com imutabilidade do objeto inteiro: `MigrationScope` compartilha uma lista de warnings que cresce durante a transação. Preserve esse ciclo de vida; o escopo de um item não deve ser reutilizado entre tentativas ou execuções.

## 4. Records

O ambiente usa Java 21. Um record pode representar um novo DTO, objeto de valor ou resultado pequeno, quando o consumidor aceita os acessores dos componentes e não precisa de setters, construção incremental nem identidade mutável. Ser record não determina o papel: quem decide é a responsabilidade da seção 3.

Não há records declarados atualmente nos dois serviços. Os exemplos de dados acima são classes ou enum; não converta esses tipos apenas para adotar a sintaxe. Em particular, preserve os getters e Builders já consumidos, a mutabilidade das linhas e o contexto transacional de `MigrationScope`.

Um record interno, usado só pela classe produtora e por seu teste, pode ser aninhado e privado. Um resultado compartilhado ganha arquivo próprio no pacote do papel dono. Coleções exigem cuidado com cópias e mutabilidade, porque um record só torna finais as referências dos componentes. Preserve o limite de cinco parâmetros; para dados maiores com construção incremental, mantenha a classe com Builder.

## 5. Enums

Use enum para um conjunto fechado do domínio. Dê nome que inclua o assunto, sem sufixo `Enum`; `ItemOutcome` é o exemplo existente. Um enum fica em arquivo próprio e no pacote do papel dono; se vários papéis o consomem, ele é público. Não crie uma pasta só para um enum nem importe automaticamente a organização de enums de outro módulo.

Os dois serviços possuem `ItemOutcome` em `dto`, com as constantes `SKIPPED`, `COPIED` e `LOCK_LOST`. Preserve localização, nomes e consumidores. Novos papéis ou pacotes exigem revisar o mapa de dependências; esta diretriz não muda o mapa atual.

Não altere ordem, nomes, códigos persistidos ou representação JSON como consequência de uma reorganização. Os estados, operações e modos de atualização que hoje circulam como strings em `MigrationRow`, `MigrationDao` e `MigrationApi` continuam com seus contratos; não os converta em enums sem uma tarefa que cubra banco, API, UI e testes.

## 6. Commons: reutilizável e específico

O identificador do módulo contém `commons`, mas isso não transforma o código dos serviços em biblioteca compartilhada. Ainda não existe um pacote interno de código compartilhado entre os dois serviços. A tabela abaixo define categorias para uma extração futura; não descreve diretórios já criados nem pede criá-los agora.

Pergunte nesta ordem e pare na primeira resposta positiva:

| Critério | Categoria sob o futuro pacote interno commons |
|---|---|
| Iria para outro projeto sem regra de negócio nem adaptação? | `utils`: utilitários técnicos. |
| É lógica usada por mais de um serviço, sem ser um serviço LumisXP? | `service/<lógica>`: uma responsabilidade reutilizável por agrupamento. |
| É script de estilo repetido entre serviços e ligado ao tema? | `helper`: apoio de apresentação compartilhado. |
| Configura biblioteca de terceiros ou contexto global, como JSON ou JAX-RS? | `config/<área>`: configuração compartilhada. |
| É exceção global, sem pertencer a um serviço específico? | `exceptions`: falhas compartilhadas. |
| Nenhuma dessas condições se aplica? | Mantenha no pacote do papel do serviço dono. |

- Commons nunca importa de `service/<serviço>` do módulo. A dependência parte dos serviços para a lógica compartilhada; commons devolve dados e códigos, e a borda do serviço traduz textos pelas strings Lumis e expõe REST ou DOUI.
- Uma lógica usada por um só serviço fica nele, mesmo que pareça genérica. Ela sobe para commons quando o segundo serviço precisar, no mesmo trabalho que ajusta consumidores, contratos e testes.
- Ter nomes iguais não basta para extrair. Compare comportamento, tipos de dados, tabelas, configuração, permissões e transações antes de definir a interface compartilhada.

### Dívida conhecida: duplicação entre os serviços

`contentmigration` e `hierarchicalcontentmigration` duplicam parte de `copy`, `dao`, `dependency`, `dto`, `execution` e outros papéis. Exemplos reais são `AuthorshipCloner`, `DependencyHandler`, `ExecutionSettings` e `MigrationScope`. A duplicação é dívida conhecida; a tarefa de documentação não move código nem cria o pacote commons.

A extração futura precisa considerar as diferenças: `HierarchyTree` e `MigrationSelection` atendem a seleção hierárquica, `ContentMigrationCoexistenceDao` consulta o serviço integral sem importar suas classes, e cada serviço possui seus tipos e configuração. Mantenha a independência de instalação e os contratos de cada um ao avaliar o que pode ser compartilhado.

## 7. Alterar, mover ou renomear

No mesmo trabalho, atualize imports, testes em `test/java`, nomes de classe em texto, XML, referências da ponte e documentação afetada; remova os `.class` antigos. Não faça mudanças de nomes fora do serviço coberto pela tarefa. Testes, fixtures e ponte permanecem em `test/`.

Todo elemento Java novo ou alterado recebe Javadoc em inglês com `@since %LUM_NEXT_VERSION%`, preservando o `@since` dos demais e sem `@author`. Documentação e interface são em português; mensagens visíveis vêm das strings Lumis do serviço. Verifique estilo, estrutura e documentação com `bash test/run-static-checks.sh`; mudanças de comportamento também precisam dos testes pertinentes pelo harness descrito em [test/AGENTS.md](../test/AGENTS.md).
