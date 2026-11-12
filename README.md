# Migração de conteúdo entre instâncias

> O módulo também traz o serviço `hierarchicalcontentmigration`, que migra só conteúdos hierárquicos escolhidos pelo usuário, com seus descendentes. Veja [docs/hierarchicalcontentmigration.md](docs/hierarchicalcontentmigration.md).

O módulo `br.com.lumis.commons.contentmigrator` fornece o serviço `br.com.lumis.commons.contentmigrator.service.contentmigration` para copiar conteúdos entre duas instâncias do mesmo serviço Lumis Portal. A cópia inclui as versões publicada e ativa, metadados suportados e referências às dependências configuradas.

## Documentação

- [Requisitos e comportamento da migração de conteúdo](docs/requisitos/requisitos-migracao-conteudo.md): requisitos verificáveis, como o serviço os atende e o que decidir na adoção.
- [Visão geral da migração de conteúdo](docs/visao-geral/visao-geral-migracao-conteudo.html): os mesmos requisitos em uma página HTML, com diagramas da execução e da reversão.
- [Migração de conteúdo hierárquico](docs/hierarchicalcontentmigration.md): diferenças, uso e limites do serviço `hierarchicalcontentmigration`.
- [Operar o módulo no Portal](docs/OPERAR-MODULO-NO-PORTAL.md): instalação, compilação, publicação, remoção e diagnóstico.
- [Decisões técnicas](docs/DECISOES-TECNICAS.md), [diretrizes de código](docs/DIRETRIZES-CODIGO.md) e [guia de código](docs/guia-de-codigo.md).
- Arquitetura por dependência: [mídia](docs/architecture/media-migration.md), [documento](docs/architecture/document-migration.md), [widget](docs/architecture/widget-migration.md) e [metadados de conteúdo](docs/architecture/content-metadata-migration.md).
- [Guia dos arquivos AGENTS.md](docs/GUIA-AGENTS-MD.md): critérios das instruções para agentes de código.

Estudos de apoio ficam em `docs/internal/`: evidências de plataforma, mapa de nomes, compatibilidade com o LumisXP 19.0.0, desenho dos DTOs e da visão geral em HTML e relatórios de revisão.

## Instalação

### Compatibilidade

- Lumis Portal 19.0.0.
- Código compilado para Java 21.
- Duas instâncias distintas do mesmo serviço de conteúdo. Serviços de repositório e serviços DOUI não podem ser migrados como conteúdo.

### Implantar e instalar

1. Publique o módulo `br.com.lumis.commons.contentmigrator` no Lumis Portal pelo processo padrão de atualização de módulos do ambiente.
2. Instale o componente do módulo pelo processo padrão do Portal. O script de instalação aplica a definição do banco e cria as tabelas de controle da migração.
3. Crie uma instância do serviço **Migração de conteúdo** em um canal administrativo.
4. Conceda aos operadores permissão para visualizar a origem e editar o destino. Para mapear widgets, eles também precisam editar o repositório de widgets do destino.
5. Configure as dependências das instâncias de conteúdo. Para mídia, documento, categorização, tags e widgets, a origem e o destino precisam de repositórios do mesmo tipo. Repositórios de modelos de e-mail e termos de privacidade precisam apontar para a mesma instância nos dois lados.

Antes de criar uma migração, o serviço valida que origem e destino usam o mesmo serviço, o mesmo workflow e dependências compatíveis. Fontes `table` e campos do tipo `files` bloqueiam a criação.

### Desinstalar

Pare as migrações em execução e faça backup antes de desinstalar o componente. O script de desinstalação remove `lum_cmig_MigLock`, `lum_cmig_MigWidgetMap`, `lum_cmig_MigRepoMap`, `lum_cmig_MigItemMap` e `lum_cmig_Migration`. Ele não apaga conteúdos nem arquivos do destino, mas remove os status e mapeamentos necessários para retomar ou desfazer migrações.

O componente `hierarchicalcontentmigration` tem script de desinstalação próprio, que remove `lum_cmig_HMigSelection`, `lum_cmig_HMigLock`, `lum_cmig_HMigWidgetMap`, `lum_cmig_HMigRepoMap`, `lum_cmig_HMigItemMap` e `lum_cmig_HMigration`, sem tocar nas tabelas `lum_cmig_Mig*`. Ele também mantém os conteúdos do destino e remove as seleções e os mapas das migrações hierárquicas. Os passos estão em [docs/hierarchicalcontentmigration.md](docs/hierarchicalcontentmigration.md).

## Uso da administração

Abra a interface **Administração** da instância do serviço.

1. Selecione **Nova migração** e informe o nome.
2. Escolha o serviço de conteúdo. As listas de origem e destino mostram instâncias visíveis ao operador e são carregadas em sequência; escolha instâncias diferentes.
3. Selecione **Criar**. As regras de workflow, dependências e fontes são verificadas antes do registro.
4. Selecione a migração e use **Iniciar incremental** para copiar somente os conteúdos ainda sem mapa, ou **Iniciar atualização** para reaplicar a origem sobre conteúdos já mapeados.
5. Acompanhe status, total e quantidade migrada. A lista consulta o endpoint REST de status a cada 60 segundos.
6. Use **Parar** para solicitar parada entre conteúdos. Um novo início retoma pelos mapas existentes.

Estados exibidos: **Parado**, **Parado com erro**, **Em execução** e **Finalizado**. Cada conteúdo e seu mapa são commitados na mesma transação. Uma falha fica registrada com mensagem e stack, acessíveis por **Detalhes do erro**.

Quando um conteúdo contém um widget sem mapeamento, a execução para com erro. Abra **Mapear widget**, confira o conteúdo bloqueante e o widget de origem, escolha o widget paralelo no repositório de destino e salve. Inicie a migração novamente para retomar.

As ações **Excluir** e **Desfazer** têm efeitos diferentes:

- **Excluir** remove o registro da migração e seus mapas de conteúdo, mas mantém os conteúdos no destino. Mapeamentos de repositório e widget compartilhados são mantidos enquanto outra migração precisar deles. Recriar a migração para o mesmo par pode duplicar conteúdos.
- **Desfazer** tenta remover os conteúdos e clones criados pela migração quando não há referências. Itens preexistentes ou usados por outro conteúdo são mantidos; o resultado pode conter avisos. Mapeamentos de widgets permanecem.

## Modelo de dados

As tabelas do módulo são fontes `table`, separadas dos conteúdos Lumis:

| Tabela | Finalidade |
| --- | --- |
| `lum_cmig_Migration` | Uma migração por par de instâncias; guarda modo, operação, status, contagens, erro e autor. |
| `lum_cmig_MigItemMap` | Liga cada `ContentLocale` de origem ao destino por par de instâncias, fonte e locale; registra se o destino foi criado pelo módulo. |
| `lum_cmig_MigRepoMap` | Registra o par de repositórios quando a migração é criada. O desfazer e a exclusão consultam as dependências atuais; alterá-las depois pode deixar clones do par antigo sem limpeza. |
| `lum_cmig_MigWidgetMap` | Liga o widget de origem ao widget escolhido no repositório de destino. |
| `lum_cmig_MigLock` | Mantém o lock global e o heartbeat da execução ativa. |

O mapa de conteúdo usa a identidade estável de `ContentLocale`, não o ID de uma versão específica. Assim, a mesma linha pode apontar para os novos itens após uma atualização.

O serviço `hierarchicalcontentmigration` usa tabelas próprias, com a mesma finalidade das anteriores e sem compartilhar linhas com elas:

| Tabela | Finalidade |
| --- | --- |
| `lum_cmig_HMigration` | Uma migração hierárquica por par de instâncias. |
| `lum_cmig_HMigSelection` | Marcas mínimas da seleção de cada migração; os descendentes são calculados a cada execução. |
| `lum_cmig_HMigItemMap` | Mapa de `ContentLocale` de origem para o destino, com os avisos do item. |
| `lum_cmig_HMigRepoMap` | Pares de repositórios registrados na criação. |
| `lum_cmig_HMigWidgetMap` | Widget de origem e widget paralelo escolhido no destino. |
| `lum_cmig_HMigLock` | Trava global e heartbeat das execuções hierárquicas. |

O serviço hierárquico lê `lum_cmig_Migration` e `lum_cmig_MigLock` para recusar o mesmo par e execuções simultâneas no mesmo destino. O `contentmigration` não consulta as tabelas `lum_cmig_HMig*`; por isso, não use o `contentmigration` em instâncias hierárquicas que já têm migração hierárquica.

## Organização do código

O código Java fica em `src/br/com/lumis/commons/contentmigrator/service/contentmigration`, dividido por responsabilidade: `api` (fachada `MigrationApi`), `dataprovider`, `processaction`, `query` e `rest` (adaptadores do Lumis), `execution`, `copy`, `undo`, `dependency`, `dao` e `dto`. Os testes ficam em `test/java`, separados por aquilo que verificam. O estilo (TAB, chaves Allman, 170 colunas, condições com até dois termos) e a direção das dependências entre pacotes estão no [guia de código](docs/guia-de-codigo.md), com as ferramentas `test/format.sh`, `test/check_style.py` e `test/check_structure.py`. Os documentos de [mídia](docs/architecture/media-migration.md), [documento](docs/architecture/document-migration.md), [widget](docs/architecture/widget-migration.md) e [metadados de conteúdo](docs/architecture/content-metadata-migration.md) registram as decisões de cada dependência.

Nenhum método ou construtor de `src` recebe mais de 5 parâmetros; `test/check_style.py` aplica esse limite, e a única exceção é `// style:many-params <motivo>` na linha anterior à declaração. Os valores que se repetiam de método em método ficam em dois DTOs de `dto`:

- `MigrationScope` reúne sessão, transação, conexão, `serviceId`, instâncias de origem e destino, sources, modo de atualização e a lista de warnings do item. Tem 10 campos e por isso nasce de `MigrationScope.builder()`, que valida os obrigatórios em `build()`.
- `VersionCopy` descreve uma versão de origem a gravar no destino: a linha e a versão de origem e os ids de destino (conteúdo, `ContentLocale` e item, nulos numa inclusão). `hasDestinationLocale()` escolhe entre `updateData` e `addData`.

Fluxo de criação por item: `ItemProcessor.processOnce` abre a transação, monta um `MigrationScope` e abre o `FileCloner`; `ItemDependencies.create(scope, fileCloner)` monta um `DependencyContext(scope, repositories, fileCloner)` por handler. Para cada versão a copiar, `copyVersions` monta um `VersionCopy` e chama `copySingleVersion(scope, dependencies, version)`, que passa por `ContentCopier.buildData(scope, dependencies, version)` e `applyAfterWrite`. Em seguida, `copyLocaleMetadata` copia estado, publicação, tags e autoria, e `saveItemMap` grava o mapa. Uma nova tentativa do item cria outro escopo, com warnings vazios. O desfazer de cada clone de repositório monta o seu escopo em `MigrationUndoer.undoRepositoryItem`, sem sources e com `updateMode` falso.

## Testes

Use o harness do módulo, que publica o código no `portal_install`, compila os testes e inicia uma JVM Portal própria. Ele requer o ambiente local configurado, banco MySQL e Elasticsearch disponíveis; usa o `ai_tools` configurado para o workspace e para/restaura o Tomcat conforme o estado inicial.

```bash
bash test/run-tests.sh FullMigrationTest
bash test/run-tests.sh --all
```

`--all` executa todas as classes `*Test` encontradas. Não execute o JUnit em paralelo com o Tomcat usando o mesmo banco.

O runner compila o módulo antes do deploy e preserva o módulo instalado para restaurá-lo se o deploy não concluir.

## Decisões de arquitetura

- A execução usa o `ExecutorManager` do Portal e um lock global persistido no banco. Só uma migração roda por vez neste módulo.
- O processamento confirma cada conteúdo em transação própria; status, contagens e heartbeat usam transações curtas separadas. Uma parada ou retomada não desfaz itens já commitados.
- Os mapas de conteúdo permitem retomar sem duplicar itens já processados. O desfazer e a exclusão recalculam os pares de repositórios a partir das dependências atuais; mantenha essas dependências até decidir como tratar clones antigos quando a configuração muda.
- Mídia, documento e termos de categorização têm cópia e mapeamento próprios. Pastas são resolvidas por hierarquia; arquivos clonados recebem identidade própria; links de arquivo conhecidos são reescritos.
- Widgets são vinculados por mapeamento explícito para um widget paralelo no destino. O widget de origem não é clonado nem configurado automaticamente para o contexto de destino.
- São copiadas as versões publicada e ativa, além de workflow, autoria, comentários, tags e associações suportadas. Permissões do conteúdo não são copiadas.
- O modo incremental adiciona itens sem mapa. O modo atualização reaplica os dados de origem aos itens mapeados. Excluir preserva o conteúdo de destino; desfazer remove somente itens criados e sem referências identificadas.

## Limitações e evolução

### Escopo e fontes

- **Repositórios `email_template` e `privacyterm`:** não são clonados; o destino precisa compartilhar a mesma instância da origem. Instâncias diferentes não podem migrar esse tipo de dependência. **Evolução:** implementar handlers e mapas próprios ou manter uma política explícita de compartilhamento.
- **Campos `files`:** bloqueiam a criação da migração. Serviços com esses campos não podem ser migrados por este módulo. **Evolução:** criar handler de cópia e reescrita de referências para esse tipo.
- **Sources `table`:** bloqueiam a criação mesmo em um serviço de conteúdo. Os dados dessas fontes não entram no mapa. **Evolução:** definir adaptadores por fonte, incluindo identidade, dependências e política de exclusão.
- **Versões antigas:** somente a versão publicada e a ativa são copiadas. Histórico anterior não é preservado. **Evolução:** definir como copiar a linha do tempo de versões, autoria, comentários e estados.
- **Widgets sem mapeamento:** não há prévia ou simulação de mapeamentos antes da execução; o primeiro conteúdo afetado para com erro. **Evolução:** adicionar uma etapa de pré-validação que liste todos os widgets sem destino e permita mapeá-los antes de iniciar.

### Dados e referências

- **Links HTML para páginas e conteúdos da origem:** links internos que não sejam referências reconhecidas de mídia, documento ou widget permanecem apontando para a origem. **Evolução:** mapear páginas e conteúdos por URL, canal e instância, e reescrever esses links.
- **Conteúdo apagado na origem:** a exclusão não é propagada ao destino; o item de destino pode ficar desatualizado. **Evolução:** adicionar sincronização de exclusões com tombstones e uma opção explícita de propagação.
- **Publicação cruzada:** destinos de publicação para outras instâncias não são migrados. **Evolução:** mapear instâncias de publicação e validar os alvos antes da cópia.
- **Excluir e recriar:** Excluir mantém os conteúdos e remove os mapas de conteúdo. Recriar para o mesmo par pode criar cópias duplicadas. **Evolução:** reconciliar conteúdos existentes ao recriar ou exigir uma confirmação com opção de recuperar os mapas.

### Desfazer

- **Edições no destino:** desfazer pode apagar conteúdo criado pela migração mesmo que alguém o tenha editado depois. **Evolução:** comparar versão ou fingerprint com o estado criado e pedir decisão quando houver alteração.
- **Filhos ou associações nativas:** conteúdos referenciados podem permanecer com aviso, deixando o desfazer parcial. **Evolução:** exibir o grafo de referências e oferecer um plano de remoção ou retenção antes de executar.
- **Pastas e termos preexistentes:** objetos reutilizados nunca são apagados, mesmo quando ficam sem uso após o desfazer. **Evolução:** registrar propriedade e uso para oferecer limpeza somente de clones criados pelo módulo e sem referências.

### Permissões e operação

- **Permissões de leitura e de pastas:** não são copiadas. Os operadores precisam configurar acessos equivalentes nos canais e repositórios de destino. **Evolução:** criar mapeamento explícito de ACLs, sem copiar permissões por padrão.
- **Limites de upload:** o módulo não oferece limite próprio nem pré-validação de tamanho; falhas dependem das configurações do Portal e do servidor. **Evolução:** documentar um limite operacional e validar tamanho antes de copiar.
- **Pool do `ExecutorManager`:** a execução usa o pool compartilhado do Portal; uma migração longa ocupa recursos compartilhados. **Evolução:** adicionar fila, limites de concorrência e métricas próprias.
- **Cluster:** o lock é global no banco, mas o trabalho fica na memória do nó que recebeu o início. **Evolução:** usar fila durável e propriedade explícita do nó com recuperação distribuída.
- **Reinício ou redeploy:** trabalho em memória pode parar entre conteúdos; commits anteriores permanecem e podem ser retomados após o lock ficar obsoleto, mas a recuperação não é automática. **Evolução:** detectar execuções interrompidas, registrar a causa e oferecer retomada controlada.
- **Notificações de workflow:** estados, autores e comentários são copiados sem disparar notificações ou transições de workflow. **Evolução:** integrar eventos nativos depois de definir destinatários e evitar notificações duplicadas.

### Plataforma e entrega

- **Transformações de mídia:** a configuração e os arquivos derivados não são migrados como artefatos independentes; links transformados dependem da transformação existir no destino. **Evolução:** mapear transformações e copiar ou regenerar os artefatos necessários.
- **Oracle e SQL Server:** não foram validados pelo conjunto de integração deste módulo. **Evolução:** incluir esses bancos na matriz de testes antes de declarar compatibilidade.
- **Windows:** não há launcher `run-tests.cmd`; o harness disponível usa Bash. **Evolução:** fornecer wrapper `.cmd` ou PowerShell com as mesmas etapas e garantias.
- **Ambiente de desenvolvimento:** o harness de testes e o guia de operação dependem das ferramentas `ai_tools` do ambiente LumisXP e de uma instalação `portal_install`, externas a este repositório; as verificações dos estudos de plataforma também leem as fontes do LumisXP. **Evolução:** documentar a preparação mínima desse ambiente ou oferecer um harness independente dessas ferramentas.

## Verificação manual da interface

1. Em um ambiente de teste, instale o módulo e crie a instância **Migração de conteúdo**. Crie duas instâncias do mesmo serviço de conteúdo em canais distintos e configure as dependências compatíveis.
2. Abra **Nova migração**. Verifique que o serviço mostra apenas serviços de conteúdo com instâncias visíveis, que as listas de instâncias são encadeadas e que a própria instância não aparece como origem ou destino.
3. Crie a migração e confira a linha, o status, o total e o número de itens migrados. Inicie incremental e confirme a atualização dos dados na lista em até 60 segundos.
4. Pare uma migração longa, confirme o estado **Parado** e inicie incremental outra vez. Verifique que itens já mapeados não são duplicados.
5. Altere um conteúdo na origem, inicie **Iniciar atualização** e confira a versão publicada e ativa no destino.
6. Com um widget de origem sem mapa, inicie uma migração. Abra **Mapear widget**, confirme que conteúdo e widget de origem são somente leitura, escolha o widget paralelo do destino, salve e retome.
7. Provoque uma falha de validação em um ambiente descartável e abra **Detalhes do erro**. Confira mensagem e stack.
8. Compare **Excluir** e **Desfazer** em migrações separadas: Excluir deve manter conteúdo; Desfazer deve remover somente conteúdos e clones criados e sem referências, mantendo os itens ainda usados.
