# Atualização do Content Migrator para LumisXP 19.0.0

Estudo de compatibilidade realizado em 12/11/2026, quando o módulo foi portado da 18.1.1 para a 19.0.0. As tabelas registram o que cada destaque de versão exige do módulo e o que o levantamento recomendou. A seção “Decisão vigente” registra o que foi adotado e prevalece sobre as recomendações anteriores do mesmo estudo.

## Fontes e convenções

Caminhos de plataforma são relativos à raiz de um checkout `Lumis_PortalJava/`; no ambiente de desenvolvimento, essa raiz fica cinco níveis acima da raiz do módulo.

- **D1811**: `portal/src/java/lumis/portal/documentationfiles/pt-BR/lumis.installation_and_configuration.versions_highlights.version_18.version_18.1.1.html`.
- **D1812**: `portal/src/java/lumis/portal/documentationfiles/pt-BR/lumis.installation_and_configuration.versions_highlights.version_18.version_18.1.2.html`.
- **D1900**: `portal/src/java/lumis/portal/documentationfiles/pt-BR/lumis.installation_and_configuration.versions_highlights.version_19.version_19.0.0.html`. A leitura usou a versão de trabalho desse destaque no checkout local; as conclusões não foram reconferidas contra a versão publicada.
- **HIER**: `lumisxp-service-instructions/lumis.service.hierarchicalcontent.md`, do pacote de referência de IA do LumisXP, guia de menu e breadcrumb. Define o vínculo por `parentContentId`, ordenação por `position`, ocultação por `hidden`, identificação por `contentId`, destinos por `$href` e comportamento de `primaryContent` no breadcrumb.
- **META19**: `content/src/java/lumis/content/source/field/MetaDataSourceField.java`, enum `Type` e leitura dos atributos XML; `content/src/java/lumis/content/table/ContentTableSource.java`, reconhecimento dos novos campos.
- **BUILD**: `build.properties`, propriedades `java.targetVersion`, `version.major/minor/build/release` e `elasticsearch.client.version/server.version`; `portal/build.gradle`, dependências da plataforma.

Nas tabelas, **C/** significa `src/br/com/lumis/commons/contentmigrator/service/contentmigration/`, **H/** significa `src/br/com/lumis/commons/contentmigrator/service/hierarchicalcontentmigration/`; **TC/** e **TH/** significam os respectivos pacotes sob `test/java/br/com/lumis/commons/contentmigrator/service/`. Assim, `C/copy/MetadataPolicy.java` identifica um arquivo concreto do módulo.

## Itens relevantes

### 18.1.1

D1811 contém correção de tags HTML reordenadas na renderização com cache. Efeito indireto em `C/copy/ContentCopier.java` e `H/copy/ContentCopier.java`, que transportam os valores de campos, e nos testes `TC/test/spike/WidgetLinkSpikeTest.java` e `TH/test/migration/WidgetWorkflowMigrationTest.java`: comparar HTML persistido e seus vínculos, sem reproduzir no migrador a correção do renderizador. Não há mudança de API exigida por esse destaque. A recriação do índice de conhecimento de IA e a reindexação condicional são observações operacionais do portal; o módulo não administra esse índice.

### 18.1.2

D1812 adiciona suporte ao MariaDB Connector/J para MySQL em desenvolvimento. Impacto indireto em `C/dao/Transactions.java`, `H/dao/Transactions.java` e nos DAOs dos dois serviços: usam a conexão JDBC do portal, não instanciam driver. `pom.xml` não declara driver MySQL/MariaDB. Não adicionar dependência nem substituir SQL por causa desse suporte opcional; a escolha do driver pertence à configuração do portal.

### 19.0.0

Linhas que pedem conferência registram verificações recomendadas pelo levantamento; este documento não registra seus resultados.

| Item e fonte | Arquivos afetados e consequência |
| --- | --- |
| Migração Java EE → Jakarta EE e Tomcat 11; D1900, Observações | `C/rest/MigrationStatusResource.java`, `H/rest/MigrationStatusResource.java` e `H/rest/HierarchicalContentBrowserResource.java` importavam `javax.ws.rs` na versão portada. Hoje importam `jakarta.ws.rs` e são compilados contra a plataforma 19.0.0. |
| REST Jakarta/Jersey 3.1; D1900, Observações | Os três recursos anteriores e `TC/test/admin/StatusRestTest.java`, `TH/test/admin/StatusRestTest.java`, `TH/test/admin/HierarchyBrowserRestTest.java` usavam anotações ou respostas JAX-RS antigas. Recursos e testes foram migrados juntos, para evitar mistura de tipos de resposta. |
| Java 21; BUILD | `pom.xml` configurava source/target 11 na versão portada; hoje fixa 21 em source, target e release. `test/run-tests.sh` obtém Java de `JAVA_HOME` ou do ambiente das ferramentas. Esse requisito foi confirmado no build; não está explicitado no texto dos destaques. |
| JPA Jakarta e Hibernate 7.2.7.Final; D1900, Observações | Os pacotes `C/dao`, `H/dao`, `C/query`, `H/query` e os fixtures usam JDBC/API do portal. Busca em src e test não encontrou import de Hibernate/JPA, `createSQLQuery` ou `SQLQuery`. Não converter PreparedStatement nem seus parâmetros para a sintaxe de Hibernate. Recompilar os consumidores de APIs do portal. |
| Definições de tabela e DOUI; D1900, Observações e DOUI / Content | `C/componentfiles/setup/databasedefinition.xml`, `H/componentfiles/setup/databasedefinition.xml` e ambos os `douidefinition.xml`: não há atributo collate binary atingido pela mudança de SQL Server. Manter coerência de tipos/tabelas; novos operadores notLike são opcionais, sem necessidade encontrada nas definições atuais. |
| SEO: recalcular URL e adicionar redirecionamento; D1900, Serviços Nativos; META19 | `C/copy/MetadataPolicy.java`, `H/copy/MetadataPolicy.java` e ambos os `copy/ContentCopier.java`: na versão portada, os novos tipos não entravam na lista de cópia e eram omitidos sem aviso. A seção “Decisão vigente” define a política explícita. |
| Valor padrão de robots em ContentTableSource.addData; D1900, Correções DOUI / Content | Ambos os `copy/MetadataPolicy.java` já permitem `RobotsConfiguration`, e ambos os `copy/ContentCopier.java` montam parâmetros de addData/updateData. Preservar valor explícito e conferir herança quando ausente, sem fixar um novo padrão local. |
| Localização, Unicode e idioma em execução assíncrona; D1900, Correções | `C/copy/MetadataPolicy.java`, `H/copy/MetadataPolicy.java` resolvem slugs; `H/hierarchy/HierarchyQueries.java` seleciona locale padrão/fallback; ambos os `execution/MigrationTask.java` executam trabalho assíncrono. Conferir idiomas e URLs Unicode; não introduzir transliteração ou assumir locale da thread. |
| URLs de ancestrais e navegação hierárquica; D1900, Serviços Nativos; HIER | `H/hierarchy/HierarchyQueries.java`, `H/hierarchy/HierarchyTree.java`, `H/copy/ContentCopier.java`, `H/copy/LinkFieldPolicy.java`: preservar remapeamento de pais e metadado UrlTitle, pois o resolver nativo passa a usar o título para URL dos ancestrais. Não recompor URL final com IDs ou caminho da origem. |
| Criação inicial de conteúdo, layout e widgets; D1900, Serviços Nativos e Correções | `H/config/HierarchicalContentService.java`, `H/dependency/widget/WidgetDependencyHandler.java`, `H/copy/LinkFieldPolicy.java` e `TH/test/migration/WidgetWorkflowMigrationTest.java`: verificar instância vazia e troca de layout com widgets. Criação automática pelo layout agora exige exatamente uma interface de detalhes; não depender desse efeito para construir a árvore migrada. |
| Metadado de descrição de Mídias e componentes HTML multibloco; D1900, Melhorias | `C/dependency/media/MediaDependencyHandler.java`, `H/dependency/media/MediaDependencyHandler.java`, ambos os `copy/ContentCopier.java` e `dependency/widget/WidgetDependencyHandler.java`: conferir transporte de campos da source e HTML completo, preservando descrição e referências sem reconstrução de blocos. |
| Workflow e expiração de cache; D1900, Correções DOUI / Content | `C/copy/WorkflowCloner.java`, `H/copy/WorkflowCloner.java`: observar o estado final via API nativa e visibilidade do conteúdo; não adicionar invalidação paralela só por causa da correção do produto. |
| Elasticsearch 8; BUILD e correções de indexação em D1900 | `test/ensure-es.sh` esperava o cluster e o log da 18.1.1 na versão portada; hoje confere o cluster do ambiente 19.0.0 e grava o log com `19-0-0` no nome. O build usa servidor 8.17.1 e cliente 8.19.16; os JARs instalados incluem elasticsearch-java-8.19.16. A separação entre versão do servidor e do cliente é preservada. Não há cliente Elasticsearch direto em src. |
| Bibliotecas/APIs removidas ou substituídas; D1900, Observações | Portlet/WSRP removidos; SOAP migra de javax.jws/javax.xml.ws para Jakarta; JAXB usa jakarta.xml.bind; upload migra a commons-fileupload2. Busca em src, test e pom.xml não encontrou consumidores dessas APIs. Impacto: revisar dependências transitivas do pom, sem inventar adaptações em código sem consumidor. |

## Impacto no módulo

### Versão efetivamente disponível e recomendação do POM

A versão portada do POM dependia de `com.lumis:lumisportal:18.1.1.260116-enterprise`. A inspeção de `portal_install/www/WEB-INF/lib` encontrou os artefatos separados da plataforma como `lumisportal-portal-0.0.0.000000.jar`, `lumisportal-content-0.0.0.000000.jar` etc. O manifesto de portal confirma **Implementation-Version: 0.0.0.000000** e **Specification-Version: 0.0.0**. Não há pom.properties com coordenada com.lumis nesses JARs. Portanto o nome da pasta do ambiente não prova uma coordenada Maven publicada exata.

O build do checkout consultado declara `version.release=19.0.0.260630` por expansão das propriedades. O cache Maven local continha tanto `19.0.0.260630-enterprise` quanto `19.0.0.260726-enterprise`; a mera presença não decide qual corresponde ao runtime. O levantamento recomendou confirmar a correspondência entre os binários instalados e o artefato Maven antes de fixar a versão: **19.0.0.260630-enterprise era a candidata baseada no BUILD, não uma identificação comprovada dos JARs instalados**. Sem correspondência, a alternativa é o artefato gerado pelo mesmo build do portal. Não deduzir o sufixo pela data de lançamento no destaque. Hibernate 7.2.7.Final, Jersey 3.1.11 e Jakarta Persistence 3.2.0 presentes no WEB-INF/lib confirmam a stack Jakarta, mas não resolvem essa ambiguidade de build.

### Política de metadados e risco de perda silenciosa

META19 introduz `MetaDataSourceField.Type.RecalculateFriendlyURLs` e `MetaDataSourceField.Type.AddFriendlyURLsRedirects`, ambos com `@since 19.0.0`. O Javadoc desses tipos os associa aos getters `ContentVersion.isRecalculateFriendlyURLs()` e `ContentVersion.isAddFriendlyURLsRedirects()`; os atributos de definição são `isRecalculateFriendlyURLs` e `isAddFriendlyURLsRedirects`.

Na versão portada, `shouldCopyAsParameter` dos dois `copy/MetadataPolicy.java` incluía UrlTitle, UrlPath e RobotsConfiguration, mas retornava false no default para os dois novos tipos. Em ambos os `copy/ContentCopier.java`, `tryHandleSpecialMetadataField` termina em `!MetadataPolicy.shouldCopyAsParameter(metadata)`: true significa campo já tratado e impede sua cópia normal. Assim os novos booleanos eram omitidos sem erro de compilação. O levantamento propôs preservar esses valores; a inspeção registrada em “Decisão vigente” mostrou que são comandos transitórios e substituiu essa proposta. Copiar a preferência não significaria copiar registros de redirecionamento ou URLs geradas da instância de origem.

### Resultado das buscas de compatibilidade

Foram cruzados imports Java, XMLs de serviço/tabela/DOUI, SQL em Java e scripts. Os imports javax encontrados eram JAX-RS nos recursos e testes listados e APIs Java SE: `javax.imageio.ImageIO` em `TC/test/spike/MediaCloneSpikeTest.java` e `javax.xml.parsers.DocumentBuilderFactory` em `TH/test/admin/MigrationProcessActionHandlerTest.java`. **Essas duas APIs Java SE são mantidas**; não se substitui indiscriminadamente todo prefixo javax. Não houve ocorrência de javax.xml.bind, javax.jws, javax.xml.ws, javax.persistence, javax.servlet ou javax.validation no código pesquisado.

Não houve import de org.hibernate, uso de SQLQuery/createSQLQuery ou configuração cache.provider_class. `C/dao/Transactions.java` e `H/dao/Transactions.java` obtêm Connection por ITransactionJdbc; DAOs usam PreparedStatement com índices JDBC iniciando em 1. A mudança Hibernate para createNativeQuery e parâmetros ?1 não se aplica a esse SQL. Os XMLs de tabela não contêm collate/unicode customizado; a observação de SQL Server não requer alteração local. Os namespaces Lumis de DOUI/tabela não são pacotes Java EE e não devem ser renomeados para Jakarta.

O guia HIER reforça que árvore e breadcrumb dependem dos vínculos entre conteúdos, da visibilidade e da ordenação. Os novos endpoints REST nativos contentMenu/breadCrumb não substituem automaticamente `H/rest/HierarchicalContentBrowserResource.java`, que atende seleção e navegação administrativa da migração. A troca de idioma sem tradução passa a retornar à raiz do website, e conteúdo não publicado não deve virar destino de navegação; verificar isso junto a `TH/test/migration/MultiLocaleMigrationTest.java` e `TH/test/admin/HierarchyBrowserRestTest.java` sem confundir locale do conteúdo com idioma da interface.

## Ajustes recomendados pelo levantamento

Ordem proposta pelo estudo. A seção seguinte registra a decisão adotada; `test/check_upgrade.py` verifica a base de compilação, o Java 21 e a ausência de pacotes `javax` migrados em `src` e `test/java`.

1. **Fixar a base compatível.** Confirmar a coordenada do build instalado conforme a evidência acima; atualizar a dependência lumisportal em `pom.xml` e configurar compilação Java 21, preferencialmente release 21. Não transportar dependências Servlet/Jersey/Hibernate antigas para dentro do módulo.
2. **Migrar JAX-RS.** Alterar imports para jakarta.ws.rs nos três recursos REST e nos três testes de administração citados. Preservar contratos de rota, status, permissões e JSON. Manter javax.imageio e javax.xml.parsers. Compilar fontes e testes contra a mesma plataforma.
3. **Definir a política dos novos metadados SEO nos dois serviços.** Atualizar `C/copy/MetadataPolicy.java` e `H/copy/MetadataPolicy.java`; confirmar a origem dos valores e ajustar ambos os `copy/ContentCopier.java` se necessário. Verificar RobotsConfiguration ausente/explicitamente definido. Não copiar redirecionamentos antigos como dependência.
4. **Conferir XMLs e SQL contra o portal 19.** Revisar os dois databasedefinition.xml/douidefinition.xml e consumidores JDBC dos pacotes dao/query. Preservar placeholders JDBC e tipos Lumis; só corrigir API do portal se a compilação ou execução demonstrar incompatibilidade. Não aplicar migração Hibernate a código JDBC.
5. **Reconciliar execução do harness.** Ajustar o cluster e o log de `test/ensure-es.sh` ao ambiente 19; conferir Java 21 e a instalação descoberta por `test/run-tests.sh` e `test/run-hier-tests.sh`.
6. **Verificar comportamento de conteúdo.** Usar os testes existentes de metadados, mídia/documento, widgets/workflow e multi-locale para verificar os arquivos apontados: descrição de mídia, HTML multibloco, Unicode, pais remapeados, UrlTitle de ancestral, hidden/position, primaryContent e navegação sem tradução. Verificar criação em destino vazio sem depender do conteúdo automático do layout.
7. **Concluir compatibilidade por evidência.** Compilar e executar as suítes do módulo, registrando qualquer API efetivamente removida encontrada. O estudo não identificou uso local de Portlet/WSRP, SOAP, JAXB, upload antigo, Wicket ou Atmosphere; não há adaptação direta proposta para esses itens.

Sem impacto direto adicional encontrado: índice de conhecimento e inicialização sem IA (18.1.1), e-mails do repositório de formulários (18.1.1), novos serviços de login/cadastro/landing page/identidade visual, editor de estilo e IA, comparação visual de versões, eventos de formulário/monitoramento, ajustes de Portal Studio/LEC/relatórios/reCAPTCHA e nomes visuais dos serviços nativos (19.0.0); não há consumidor correspondente nos imports, descritores ou scripts pesquisados.

## Decisão vigente

O POM usa `19.0.0.260630-enterprise`, a coordenada do build do portal disponível no repositório Maven local. `260726` continha somente marcadores `.lastUpdated`, sem binários. Os JARs instalados têm versão de desenvolvimento `0.0.0.000000`; a comparação de classes de content com o cache 260630 encontrou 376 classes comuns, 366 idênticas, entre 441 instaladas. Portanto esta coordenada é a base Maven disponível, não uma alegação de identidade binária do runtime. A compilação verifica os consumidores do módulo contra essa API.

A inspeção do código efetivo corrige a premissa do levantamento: `ContentVersion` não possui os getters citados no Javadoc de `MetaDataSourceField.Type`. Os campos `RecalculateFriendlyURLs` e `AddFriendlyURLsRedirects` são `externalData=true` em `ContentService`; não são preferências persistidas da versão. `ContentMetaDataController` recebe Boolean e Integer respectivamente, e chama `ContentUtil.setRecalculateFriendlyURLs` sobre flags transitórios de `ContentLocale` quando ambos estão presentes.

Decisão: ambos os MetadataPolicy omitem explicitamente esses dois comandos, tanto em inclusão quanto atualização, para qualquer locale. O destino mantém seu processamento nativo de URLs sem solicitar regeneração ou criação de redirecionamentos com comandos da origem. Não há fallback para getters inexistentes nem fabricação de false/0 para dados ausentes. UrlTitle, UrlPath e RobotsConfiguration continuam copiáveis. Os dois SpiRulesTest fixam a omissão dos novos tipos e a preservação da política de RobotsConfiguration e UrlTitle; sua execução exige o Portal e o harness de `test/`.

O descritor `src/META-INF/lumis-module.xml` mantém a versão do módulo `1.0` e apenas id, version e display-name, válidos no XSD. A compatibilidade Lumis 19.0.0 e Java 21 fica no README.
