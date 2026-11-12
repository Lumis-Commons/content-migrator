# Mapa de nomes do Content Migrator

Este documento fixa os nomes técnicos do módulo `br.com.lumis.commons.contentmigrator` e as exceções lidas por
`test/check_rename.py`. O módulo foi renomeado na atualização para LumisXP 19.0.0. O nome anterior do pacote e sua
abreviação nos identificadores de persistência ficam registrados somente no próprio verificador, que impede seu retorno
ao código, aos testes e à documentação e confere o limite de 30 caracteres dos nomes de persistência.

## Decisões de nomes

### Identidade e caminhos

| Elemento | Nome |
| --- | --- |
| Pacote Java raiz e ID do módulo | `br.com.lumis.commons.contentmigrator` |
| Diretório do módulo | `br.com.lumis.commons.contentmigrator` |
| Maven `groupId` | `br.com.lumis.commons` |
| Maven `artifactId` | `contentmigrator` |
| Maven `finalName` | `br.com.lumis.commons.contentmigrator` |
| Nome de exibição do módulo, Maven, coleção e tema | `Content Migrator` |
| Descrição da estrutura | `Channel component - Content Migrator` |
| Raiz de fontes e recursos | `src/br/com/lumis/commons/contentmigrator/` |
| Raiz de testes Java | `test/java/br/com/lumis/commons/contentmigrator/` |
| Serviço e componente de migração | `br.com.lumis.commons.contentmigrator.service.contentmigration` |
| Serviço e componente de migração hierárquica | `br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration` |
| Pasta da estrutura | `src/br/com/lumis/commons/contentmigrator/structure/contentmigrator/` |
| ID do componente de estrutura | `br.com.lumis.commons.contentmigrator.structure.contentmigrator` |
| Pasta do tema | `src/br/com/lumis/commons/contentmigrator/theme/contentmigrator/` |
| ID do tema | `br.com.lumis.commons.contentmigrator.theme.contentmigrator` |
| Pasta da coleção HTML | `src/br/com/lumis/commons/contentmigrator/component-collection/` |
| ID da coleção HTML | `br.com.lumis.commons.contentmigrator.component-collection` |
| Global JavaScript | `window.contentmigratorHierarchyBrowser` |
| Diretórios temporários | `contentmigrator-file-clone-*`, nos dois serviços |
| Prefixos de fixtures e arquivos de teste | `contentmigrator-test-*`, `contentmigrator-*`, `contentmigrator ...`, com expectativas e filtros de limpeza alinhados |
| Senha de usuário criada em teste | Gerada a cada execução, com prefixo fixo do módulo e parte aleatória; não é credencial de ambiente |
| Log do auxiliar Elasticsearch | `/tmp/contentmigrator-es-19-0-0.log` |

O identificador técnico usa o segmento `contentmigrator`, minúsculo e sem hífen. As classes Java, os nomes dos dois
serviços, os sufixos de propriedades e os IDs locais das interfaces foram preservados na renomeação.
`component-collection` é o diretório previsto pelo produto e permanece com essa grafia. Os GUIDs existentes não derivam
do nome do módulo e não foram alterados.

### REST e propriedades

| Contrato | Nome |
| --- | --- |
| Base REST de conteúdo | `/br/com/lumis/commons/contentmigrator/contentmigration` |
| Base REST de hierarquia | `/br/com/lumis/commons/contentmigrator/hierarchicalcontentmigration` |
| Propriedades de execução de conteúdo | `contentmigrator.contentmigration.*` |
| Propriedades de execução de hierarquia | `contentmigrator.hierarchicalcontentmigration.*` |
| Retenção de fixtures | `contentmigrator.keepTestData` |
| Cenário de UI | `contentmigrator.uiScenario` |
| Propriedade customizada de widget | `contentmigrator.migration.widget.embed` |
| Propriedade de spike | `contentmigrator.spike.embed` |

Os sufixos de produção são `heartbeatSeconds`, `itemTimeLimitSeconds`, `faultInjection.failAtItem`,
`faultInjection.itemDelayMillis` e `faultInjection.repositoryMapUniqueOnce`, nos dois serviços; a hierarquia acrescenta
`maxSelection`, `browserCacheSeconds` e `browserCacheMaxNodes`. São 17 chaves distintas contando as quatro chaves de
teste acima. Não há alias para o prefixo anterior: uma opção de JVM com esse prefixo não é lida.

Os endpoints relativos `/status`, `/contents` e `/selection` compõem o contrato REST. A base aparece junto em `@Path`,
DOUI, testes de reflexão, navegador de hierarquia e `test/ui/smoke.js`. O polling de status da migração de conteúdo
chama essa base sob o prefixo `lumis/api/rest/`, que pertence ao Portal e não ao nome do módulo.

### Tabelas e constraints

O prefixo de persistência é **`cmig`**, abreviação de Content Migrator: tabelas `lum_cmig_*` e constraints
`LUM_PK_CMIG_*` e `LUM_UN_CMIG_*`, com a distinção `H` do serviço hierárquico. `contentmigrator` por extenso não cabe
nesses identificadores: `LUM_UN_<p>_HMIGRATION_PAIR` tem 23 caracteres fixos, portanto permite no máximo 7 no prefixo
para respeitar o limite de 30 caracteres adotado para Oracle. Com `CMIG`, o maior nome tem 27.

Os nomes completos e seus comprimentos estão no apêndice de persistência: 11 tabelas e 20 constraints, sem colisões
entre os dois serviços. A busca por `lum_cmig_`, `LUM_PK_CMIG_` e `LUM_UN_CMIG_` no código do Portal 19.0.0
(`portal/src` de `Lumis_PortalJava/`) não encontrou usos. Essa busca não comprova ausência de tabelas em um banco já
instalado.

O nome em SQL, no XML de instalação/desinstalação, nos DAOs, comentários, consultas e fixtures é o mesmo. Cada
`RepositoryCloner.java` reconhece a violação de unicidade do seu mapa de itens pelo nome da constraint em minúsculas:
`lum_un_cmig_migitemmap` no serviço de conteúdo e `lum_un_cmig_hmigitemmap` no hierárquico.

### Convenções verificadas e fontes

As fontes externas a este módulo foram consultadas somente para leitura. Caminhos de plataforma são relativos a
`Lumis_PortalJava/`; os demais, à raiz do módulo.

| Regra / evidência | Fonte consultada |
| --- | --- |
| ID do módulo vem de `<id>` de `META-INF/lumis-module.xml`; `display-name` é separado | `portal/src/java/lumis/portal/deployment/Module.java`, método `readDefinition`, linhas 320–340 |
| `componentdefinition.xml`, `servicedefinition.xml`, tema e coleção são tipos distintos de objeto de deploy | `portal/src/java/lumis/portal/deployment/process/DeployObjectResolver.java`, método `resolve` |
| ID de componente, tema e coleção deriva da pasta, trocando `/` por `.`; serviço pode fornecer `id` explícito, senão usa a pasta | `portal/src/java/lumis/portal/deployment/process/AbstractFileContentBasedChecksumModuleProcessor.java`, método `resolveId`, linhas 425–474 |
| Os dois serviços não declaram `id` explícito em `<service>`; os componentes não declaram ID próprio | `src/br/com/lumis/commons/contentmigrator/service/{contentmigration,hierarchicalcontentmigration}/{servicedefinition,componentdefinition}.xml` |
| Nomes de tabela e constraint são explícitos no XML, com prefixo definido pelo projeto | Os dois `databasedefinition.xml` deste módulo |

O Portal não impõe universalmente `lum_` ou `LUM_PK_`: o módulo conserva a convenção que já usava, com o segmento
próprio `cmig`. O limite de 30 caracteres é requisito deste módulo; não é uma inferência sobre a versão ou a
configuração de um banco Oracle em execução.

## Mantidos sem renomear

| Caminho / ocorrência | Decisão e motivo |
| --- | --- |
| `test/check_rename.py` | Exceção por caminho: o verificador contém os tokens proibidos que procura. |
| `.ai-work/**` | Exceção por caminho: estado local de ferramentas de automação, ignorado pelo git e fora do produto. |
| `target/**` | Exceção por caminho: saída gerada e ignorada pelo git. Deve ser regenerada a partir dos fontes, sem edição manual. |
| Fonte CSS homônima do nome anterior | O verificador aceita esse nome somente como família completa em `font-family`, porque ele designa a fonte tipográfica. Não há uso hoje; o HTML de visão geral usa variáveis de fonte e `system-ui`. |
| GUID `publicFilesChannelId` em `src/br/com/lumis/commons/contentmigrator/theme/contentmigrator/themedefinition.xml` | Mantido: identifica um canal, não o nome do módulo. A validade do canal no destino exige conferência em runtime. |

Esta é a lista de exceções **por caminho** do verificador, sem ampliação implícita: `test/check_rename.py`,
`.ai-work/**` e `target/**`. `.git/**` é metadado do repositório e fica fora da varredura. A decisão sobre a fonte CSS
não libera as demais ocorrências de um arquivo. Documentos, inclusive este mapa e os de requisitos e de visão geral,
não são exceção.

## Riscos

- **Contrato de configuração e REST:** consumidores, JVM options e testes usam os prefixos atuais; configuração externa
  com o prefixo anterior não é lida.
- **Persistência:** os nomes atuais formam uma identidade de armazenamento própria. Tabelas de uma instalação com o nome
  anterior não são convertidas nem renomeadas (CM-D002 em `docs/DECISOES-TECNICAS.md`); reaproveitar esses dados exige
  uma decisão explícita de migração de dados, que este mapa não prescreve.
- **Detecção de unicidade:** o literal minúsculo de cada `RepositoryCloner` acompanha a constraint declarada no
  `databasedefinition.xml` do seu serviço.
- **Coexistência:** o componente hierárquico mantém tabelas `HMig*` distintas das `Mig*`; não retirar o `H` nem
  unificar locks.
- **Identidade de deploy:** mover pastas muda os IDs inferidos. Revisar todos os consumidores desses IDs,
  reflection/classloaders e recursos DOUI; atualizar só `lumis-module.xml` não basta.
- **Fixtures e limpeza:** nomes dos dados de teste e filtros de limpeza, inclusive `NoLeftoverDataTest` e o cenário de
  UI, mudam juntos. Manter um identificador antigo só na limpeza mascara dados criados no teste.
- **Referências e grafia:** `test/check_code_refs.py` resolve contra `src` as referências dos documentos HTML, inclusive
  as grafias com `<wbr>`, e `test/check_doc_links.py` confere os links de código. Mudar um pacote exige atualizar esses
  documentos no mesmo trabalho.
- **Limite da evidência:** `test/check_rename.py --scope all` prova a ausência do nome anterior e o limite de 30
  caracteres nos arquivos varridos; não prova deploy, acesso ao canal do tema, inexistência de colisões em banco ou
  funcionamento dos nomes em runtime.

## Apêndice de persistência

Cada linha abaixo vem de um dos dois `componentfiles/setup/databasedefinition.xml`. `C` indica contentmigration; `H`,
hierarchicalcontentmigration. Os comprimentos contam caracteres ASCII do identificador completo.

| Serviço | Tipo | Nome | Caracteres |
| --- | --- | --- | ---: |
| C | table | `lum_cmig_Migration` | 18 |
| C | constraint | `LUM_PK_CMIG_MIGRATION` | 21 |
| C | constraint | `LUM_UN_CMIG_MIGRATION_PAIR` | 26 |
| C | table | `lum_cmig_MigItemMap` | 19 |
| C | constraint | `LUM_PK_CMIG_MIGITEMMAP` | 22 |
| C | constraint | `LUM_UN_CMIG_MIGITEMMAP` | 22 |
| C | table | `lum_cmig_MigRepoMap` | 19 |
| C | constraint | `LUM_PK_CMIG_MIGREPOMAP` | 22 |
| C | constraint | `LUM_UN_CMIG_MIGREPOMAP` | 22 |
| C | table | `lum_cmig_MigWidgetMap` | 21 |
| C | constraint | `LUM_PK_CMIG_MIGWIDGETMAP` | 24 |
| C | constraint | `LUM_UN_CMIG_MIGWIDGETMAP` | 24 |
| C | table | `lum_cmig_MigLock` | 16 |
| C | constraint | `LUM_PK_CMIG_MIGLOCK` | 19 |
| H | table | `lum_cmig_HMigration` | 19 |
| H | constraint | `LUM_PK_CMIG_HMIGRATION` | 22 |
| H | constraint | `LUM_UN_CMIG_HMIGRATION_PAIR` | 27 |
| H | table | `lum_cmig_HMigItemMap` | 20 |
| H | constraint | `LUM_PK_CMIG_HMIGITEMMAP` | 23 |
| H | constraint | `LUM_UN_CMIG_HMIGITEMMAP` | 23 |
| H | table | `lum_cmig_HMigRepoMap` | 20 |
| H | constraint | `LUM_PK_CMIG_HMIGREPOMAP` | 23 |
| H | constraint | `LUM_UN_CMIG_HMIGREPOMAP` | 23 |
| H | table | `lum_cmig_HMigWidgetMap` | 22 |
| H | constraint | `LUM_PK_CMIG_HMIGWIDGETMAP` | 25 |
| H | constraint | `LUM_UN_CMIG_HMIGWIDGETMAP` | 25 |
| H | table | `lum_cmig_HMigSelection` | 22 |
| H | constraint | `LUM_PK_CMIG_HMIGSELECTION` | 25 |
| H | constraint | `LUM_UN_CMIG_HMIGSELECTION` | 25 |
| H | table | `lum_cmig_HMigLock` | 17 |
| H | constraint | `LUM_PK_CMIG_HMIGLOCK` | 20 |
