# Decisões técnicas do Content Migrator

Registro das escolhas vigentes. IDs são estáveis e não devem ser reutilizados. Caminhos partem da raiz do módulo. Os estudos citados preservam o levantamento histórico; a seção de decisão implementada prevalece sobre propostas anteriores do mesmo estudo.

## CM-D001 — Identidade e contratos da renomeação

**Contexto.** O módulo foi renomeado na atualização para LumisXP 19.0.0 e precisa de identidade própria, coerente entre deploy, Java, REST, propriedades e testes, preservando os dois serviços existentes.

**Escolha.** Adotar a matriz de nomes de `docs/internal/migracao-19/MAPA-RENOMEACAO.md`, seção “Decisões de nomes”. Não criar aliases legados. Conservar nomes de classes, sufixos de propriedades, IDs locais de interfaces e GUIDs que o mapa mantém. As exceções do verificador de renomeação ficam somente na seção “Mantidos sem renomear” desse mapa.

**Evidência.** O mapa confronta os resolvedores de IDs do Portal e os descritores do módulo; `src/META-INF/lumis-module.xml` materializa a identidade. `test/check_rename.py --scope all` verifica ocorrências conforme as exceções declaradas no mapa. A matriz é a fonte dos nomes completos, sem reproduzi-la neste registro.

## CM-D002 — Persistência independente

**Contexto.** Os serviços precisam de tabelas distintas entre si e de nomes de constraints compatíveis com o limite de 30 caracteres definido para esta migração.

**Escolha.** Adotar o prefixo e os sufixos definidos em “Tabelas e constraints” do mapa citado em CM-D001, preservando a distinção hierárquica. A mudança de identidade não converte dados nem renomeia tabelas de uma instalação anterior. A instalação nova e a remoção são operações explícitas do Portal, descritas em `docs/OPERAR-MODULO-NO-PORTAL.md`.

**Evidência.** O apêndice de persistência do mapa enumera 11 tabelas e 20 constraints, com maior nome de 27 caracteres. Os arquivos `componentfiles/setup/databasedefinition.xml` dos dois serviços declaram os nomes e seus `component-uninstall-script.xml` definem a remoção. A busca no código da plataforma registrada no estudo não comprova ausência de colisões em um banco instalado.

## CM-D003 — Base de compilação e APIs do LumisXP 19.0.0

**Contexto.** O runtime usa a stack Jakarta; os JARs locais têm versão de desenvolvimento e não permitem deduzir uma coordenada Maven apenas pelo nome da pasta do ambiente.

**Escolha.** Usar a base Maven fixada em `pom.xml` e Java 21, conforme a seção “Decisão vigente” de `docs/internal/migracao-19/UPGRADE-19-0-0.md`. Migrar consumidores JAX-RS para Jakarta preservando contratos. Manter APIs `javax` que pertencem ao Java SE, namespaces XML Lumis e parâmetros JDBC. Não introduzir migração de Hibernate em DAOs que usam JDBC.

**Evidência.** O estudo identifica a base disponível `19.0.0.260630-enterprise`, sua comparação parcial com os binários locais e a limitação dessa comparação. Seus IDs BUILD e D1900 fundamentam a stack; “Resultado das buscas de compatibilidade” identifica APIs usadas e ausentes. A compilação contra essa API não afirma identidade binária com todos os JARs instalados.

## CM-D004 — Comandos transitórios de URLs

**Contexto.** O estudo inicial propunha preservar dois novos metadados SEO. A inspeção posterior da API demonstrou que são comandos transitórios, não preferências persistidas de uma versão de conteúdo.

**Escolha.** Omitir explicitamente `RecalculateFriendlyURLs` e `AddFriendlyURLsRedirects` nos dois serviços, em inclusão e atualização e em qualquer locale. Não fabricar valores ausentes nem consultar getters inexistentes. Preservar a política dos metadados copiáveis de URL e robots; o destino realiza seu processamento nativo.

**Evidência.** A seção “Decisão vigente” do estudo citado em CM-D003 corrige a hipótese inicial, apoiada nas fontes META19 e no controlador de metadados. Os `copy/MetadataPolicy.java` e os `SpiRulesTest.java` de ambos os serviços materializam essa escolha. O registro da decisão não equivale à execução desses testes de Portal.

## CM-D005 — Instruções curtas e documentação roteada

**Contexto.** Repetir contratos e procedimentos nos AGENTS.md aumenta o conteúdo carregado para tarefas que não precisam desses detalhes e cria fontes divergentes.

**Escolha.** Manter regras transversais e rotas nos pais; regras exclusivas nos filhos; decisões neste registro e procedimentos no guia operacional. Os critérios, limites e checklist ficam em `docs/GUIA-AGENTS-MD.md`.

**Evidência.** O modelo consultado foi o guia equivalente de outro módulo LumisXP, adaptado às pastas deste módulo. `AGENTS.md`, `src/AGENTS.md` e `test/AGENTS.md` contêm as rotas. `test/check_agents_md.py` confere os limites, as rotas e os propósitos definidos no guia.

## CM-D006 — Compilação do módulo aberto

**Contexto.** O módulo aberto pode carregar classes diretamente de `src`; compilar apenas a saída padrão deixa essa raiz sem as classes atualizadas.

**Escolha.** Usar o perfil local já definido no POM quando a raiz aberta for `src`, preservando fontes e recursos durante `clean`. A sequência e a pendência da ferramenta externa ficam em `docs/OPERAR-MODULO-NO-PORTAL.md`, seção “Compilar e publicar”.

**Evidência.** `pom.xml`, perfil `local-development`, altera a saída do compilador sem tornar `src` o diretório de build. `UpdateRunner.java`, método `mavenCommand`, linha 1964 da cópia externa consultada, só acrescenta esse perfil para o ID de outro módulo. Não se altera a ferramenta externa nesta decisão.
