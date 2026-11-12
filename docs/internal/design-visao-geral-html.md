# Projeto visual — visão geral da migração de conteúdo em HTML

## Direção

Este documento registra o projeto visual de [visao-geral-migracao-conteudo.html](../visao-geral/visao-geral-migracao-conteudo.html), a versão de leitura dos [requisitos e comportamento da migração de conteúdo](../requisitos/requisitos-migracao-conteudo.md). O documento de requisitos é a fonte do texto; o HTML espelha seus temas `RQ-01` a `RQ-08`, a tabela de riscos, as decisões `AD-01` a `AD-07` e os termos, na mesma sequência e com os mesmos fatos.

Uma página única, autossuficiente e adequada para leitura em tela e impressão: base clara, azul discreto, títulos com hierarquia nítida, parágrafos curtos e conteúdo separado por assunto. O HTML é semântico; o CSS define apresentação. Não há dependência de JavaScript, fontes, imagens ou estilos externos. O código é citado pelo nome completo `pacote.Classe.método` em `<code class="ref">`, sem hiperlink, e verificado por `test/check_code_refs.py`; os trechos em `pre[data-source]` são verificados por `test/check_html.py` [S01, S14].

Cada decisão abaixo aponta a evidência usada e o motivo.

## Fontes pesquisadas

### S01 — [Semantics — MDN](https://developer.mozilla.org/en-US/docs/Glossary/Semantics)

Acesso: 2026-10-17. Trecho: “Screen readers can use it as a signpost”. Conclusão: escolher elementos pelo significado melhora a navegação por regiões e títulos; usar elementos semânticos para o conteúdo e CSS para sua aparência.

### S02 — [The details disclosure element — MDN](https://developer.mozilla.org/en-US/docs/Web/HTML/Reference/Elements/details)

Acesso: 2026-10-17. Trecho: “creates a disclosure widget”. Conclusão: `details` e `summary` fornecem um controle nativo, operável sem script, para recolher e expandir o sumário.

### S03 — [The figure element — MDN](https://developer.mozilla.org/en-US/docs/Web/HTML/Reference/Elements/figure)

Acesso: 2026-10-17. Trecho: “referenced as a single unit”. Conclusão: agrupar cada diagrama e sua legenda em `figure` / `figcaption` mantém a explicação junto à imagem.

### S04 — [The mark element — MDN](https://developer.mozilla.org/en-US/docs/Web/HTML/Reference/Elements/mark)

Acesso: 2026-10-17. Trecho: “marked or highlighted for reference”. Conclusão: reservar `mark` para termos cuja relevância contextual mereça destaque; não usar marcação em cada ocorrência.

### S05 — [The kbd element — MDN](https://developer.mozilla.org/en-US/docs/Web/HTML/Reference/Elements/kbd)

Acesso: 2026-10-17. Trecho: “represents user input”. Conclusão: aplicar `kbd` apenas a teclas ou entradas que a pessoa deve digitar, sem confundi-lo com código-fonte.

### S06 — [Understanding SC 1.4.3: Contrast (Minimum) — W3C WAI, WCAG 2.2](https://www.w3.org/WAI/WCAG22/Understanding/contrast-minimum.html)

Acesso: 2026-10-17. Trecho: “at least 4.5”. Conclusão: todos os pares de texto listados neste projeto mantêm pelo menos 4.5 para 1 nos temas claro e escuro, mesmo quando títulos poderiam usar o limite menor para texto grande.

### S07 — [Understanding SC 1.4.10: Reflow — W3C WAI](https://www.w3.org/WAI/WCAG21/Understanding/reflow)

Acesso: 2026-10-17. Trecho: “Make lines of text reflow within the viewport.” Conclusão: o texto deve continuar legível em viewport estreita e com zoom; só tabelas de dados podem exigir rolagem horizontal localizada.

### S08 — [Tables with Two Headers — W3C WAI](https://www.w3.org/WAI/tutorials/tables/two-headers/)

Acesso: 2026-10-17. Trecho: “use the `<th>` element” e “`scope`”. Conclusão: tabelas de dados terão legenda, cabeçalhos e escopo explícitos para identificar as relações entre linhas e colunas.

### S09 — [Typography — web.dev](https://web.dev/learn/design/typography/)

Acesso: 2026-10-17. Trecho: “max-inline-size: 66ch”. Conclusão: uma medida relativa limita linhas longas sem impedir que aumentem com o tamanho escolhido pelo leitor; entrelinha unitless acompanha o texto.

### S10 — [How Chunking Helps Content Processing — Nielsen Norman Group](https://www.nngroup.com/articles/chunking/)

Acesso: 2026-10-17. Trecho: “Short paragraphs, with white space to separate them”. Conclusão: agrupar conteúdo relacionado, usar subtítulos informativos e espaço em branco facilita a leitura por varredura sem transformar cada frase em um destaque.

### S11 — [Printing — CSS — MDN](https://developer.mozilla.org/en-US/docs/Web/CSS/Guides/Media_queries/Printing)

Acesso: 2026-10-17. Trecho: “The `print` media type sets the styles”. Conclusão: `@media print` pode remover navegação fixa, cores de fundo e efeitos que não ajudam na versão em papel ou PDF.

### S12 — [prefers-color-scheme — MDN](https://developer.mozilla.org/en-US/docs/Web/CSS/Reference/At-rules/%40media/prefers-color-scheme)

Acesso: 2026-10-17. Trecho: “prefers-color-scheme”. Conclusão: declarar o tema escuro com media query e variáveis CSS respeita a preferência do sistema sem script.

### S13 — [:target — MDN](https://developer.mozilla.org/en-US/docs/Web/CSS/Reference/Selectors/%3Atarget)

Acesso: 2026-10-17. Trecho: “derived using the document's URL fragment identifier”. Conclusão: o destino da navegação pode receber realce CSS; o estado representa a seção aberta por um link ou fragmento, não acompanha a rolagem livre.

### S14 — [SVG in HTML introduction — MDN](https://developer.mozilla.org/en-US/docs/Web/SVG/Guides/SVG_in_HTML)

Acesso: 2026-10-17. Trecho: “`<title>` provides the accessible, short-text description”. Conclusão: diagramas necessários serão SVG inline com `role="img"`, título, descrição quando preciso e legenda visível; a explicação também estará em texto.

### S15 — [Understanding SC 1.4.11: Non-text Contrast — W3C WAI, WCAG 2.2](https://www.w3.org/WAI/WCAG22/Understanding/non-text-contrast)

Acesso: 2026-10-17. Trecho: “3:1 or greater”. Conclusão: contornos que comunicam foco, estado ou limite visual devem contrastar com as cores adjacentes em pelo menos 3:1.

### S16 — [The position CSS property — MDN](https://developer.mozilla.org/en-US/docs/Web/CSS/Reference/Properties/position)

Acesso: 2026-10-17. Trecho: “nearest scrolling ancestor”. Conclusão: o sumário sticky só será aplicado quando os contêineres não capturarem a rolagem de forma inesperada; declarar `top`/`inset-block-start`.

### S17 — [:has() CSS pseudo-class — MDN](https://developer.mozilla.org/en-US/docs/Web/CSS/Reference/Selectors/%3Ahas)

Acesso: 2026-10-17. Trecho: “selecting a parent element or a previous sibling”. Conclusão: selecionar o contêiner do sumário conforme a seção alvo permite realçar o link correspondente apenas com CSS; incluir fallback visual em `:target`.

### S18 — [Using media queries — MDN](https://developer.mozilla.org/en-US/docs/Web/CSS/Guides/Media_queries/Using)

Acesso: 2026-10-17. Trecho: “browser viewport width or height”. Conclusão: breakpoints mudam a composição conforme a largura disponível; a escolha de 48rem é um token de layout a confirmar na renderização, não um limite de conformidade.

## Tokens

A paleta usa tinta azul-acinzentada sobre branco, com azul mais escuro para links e callouts; o destaque usa fundo amarelo pálido com texto escuro. No tema escuro, os fundos e os textos mudam juntos para conservar contraste. Cor nunca será a única forma de indicar estado ou importância [S06, S12, S15].

| Token CSS | Claro | Escuro | Uso |
| --- | --- | --- | --- |
| `--bg` | `#FFFFFF` | `#111827` | Fundo da página |
| `--text` | `#243447` | `#F9FAFB` | Texto principal |
| `--link` | `#005A8D` | `#93C5FD` | Links sublinhados e foco |
| `--mark-text` | `#243447` | `#F9FAFB` | Texto realçado |
| `--mark-bg` | `#FFF1B8` | `#5B4B1F` | Fundo realçado |
| `--code-text` | `#243447` | `#F3F4F6` | Código em linha ou bloco |
| `--code-bg` | `#F3F4F6` | `#1F2937` | Fundo de código |
| `--callout-text` | `#153E5C` | `#F9FAFB` | Texto de callout |
| `--callout-bg` | `#EAF2F8` | `#1F2937` | Fundo de callout |
| `--border` | `#64748B` | `#94A3B8` | Divisórias, tabelas e contornos |

Declarar no elemento `<html>` o atributo abaixo, exatamente com tokens que o CSS define em `:root`. Os valores entre parênteses são as razões calculadas com a fórmula WCAG `(L1 + 0.05) / (L2 + 0.05)`, usando luminância relativa sRGB (linearizar cada canal e ponderar R, G e B por 0.2126, 0.7152 e 0.0722). A coluna escura corresponde aos valores sobrepostos na media query de preferência escura.

```html
data-contrast-pairs="text/bg, link/bg, mark-text/mark-bg, code-text/code-bg, callout-text/callout-bg"
```

| Par declarado | Tema claro | Tema escuro |
| --- | ---: | ---: |
| `text/bg` | 12.67 para 1 | 16.98 para 1 |
| `link/bg` | 7.38 para 1 | 9.84 para 1 |
| `mark-text/mark-bg` | 11.18 para 1 | 8.13 para 1 |
| `code-text/code-bg` | 11.51 para 1 | 13.34 para 1 |
| `callout-text/callout-bg` | 9.89 para 1 | 14.05 para 1 |

Todos superam 4.5 para 1, inclusive texto auxiliar dentro de callouts e código. Link terá sublinhado; foco terá contorno visível, com cor distinta do fundo e espessura suficiente para não depender apenas da cor [S06, S15].

| Grupo de tokens | Valores | Motivo |
| --- | --- | --- |
| Espaço | `--space-1: 0.25rem`; `--space-2: 0.5rem`; `--space-3: 0.75rem`; `--space-4: 1rem`; `--space-6: 1.5rem`; `--space-8: 2rem`; `--space-12: 3rem`; `--space-16: 4rem` | Escala previsível para distinguir elementos relacionados e seções sem criar uma moldura para cada parágrafo [S10]. |
| Corpo | `font-size: 1.0625rem` (17px na configuração padrão); `line-height: 1.6`; `max-inline-size: 68ch` | Mantém texto dentro de 16–18px e entrelinha 1.5–1.7; 68ch fica na faixa recomendada de 60–75 caracteres e cresce com o zoom [S09, S10]. |
| Hierarquia | H1 `clamp(2rem, 1.5rem + 2vw, 3rem)`; H2 `1.6rem`; H3 `1.25rem`; metadado `0.95rem` | Três níveis perceptíveis por tamanho/peso, mantendo sequência sem saltos; não usar tamanho visual como substituto dos elementos de título [S01, S10]. |
| Famílias | `system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif`; código: `ui-monospace, SFMono-Regular, Consolas, monospace` | Evita recurso externo, funciona offline e preserva distinção visual para trechos literais [S01]. |

## Componentes

| Classe / elemento | Uso e estrutura | Decisão e fonte |
| --- | --- | --- |
| `.page-shell`, `.page-layout` | Centralizam a página; em tela ampla, colocam sumário e artigo lado a lado; em largura menor, empilham. | Área de leitura contida e grupos relacionados próximos; linhas do corpo permanecem limitadas em `ch` [S09, S10]. |
| `.toc` + `details` / `summary` / `nav#toc` | Sumário recolhível, com links de fragmento para todos os H2; aberto por padrão; pode ficar sticky em tela ampla, com `top` definido. | Controle nativo e acessível sem script. Sticky depende de um inset e de ancestrais sem overflow que capturem sua rolagem [S02, S13, S16]. |
| `.topics` + `.content-section` | O `<article class="topics">` reúne as seções de tema. Cada tema `RQ-01` a `RQ-08` é um `<section id>` com H2 e três subseções ordenadas: “Requisito RQ-nn”, “Como o módulo atende” e “O que avaliar na adoção”. | Mantém requisito, comportamento do módulo e decisão de adoção em blocos reconhecíveis, na ordem do documento de requisitos [S01, S10]. |
| `.requirement`, `.summary`, `.module-flow`, `.adoption` | Identificam visualmente os blocos de cada seção: requisito; síntese das seções que não são requisitos (riscos, decisões e próximos passos); como o módulo atende; o que avaliar na adoção. O texto continua compreensível sem CSS, cor ou ícone. | Hierarquia consistente torna cada bloco fácil de localizar; usar rótulos textuais, sem depender de cor [S01, S10, S15]. |
| `li id="ad-01"` … `li id="ad-07"` | Cada decisão `AD-nn` é um item com id próprio dentro da seção de decisões; os temas apontam para ele por fragmento. | O leitor chega à decisão citada sem procurar na lista; o fragmento funciona sem script [S13]. |
| `.callout`, `.callout--nature` | Chamada para a natureza do documento (escopo, limites e origem das evidências da plataforma) e observações que mudam a interpretação, como a de `maxRunTime`; `aside` só para informação complementar, não para o requisito ou o comportamento principal. | Semântica deve refletir o papel do conteúdo; contraste próprio é medido em `callout-text/callout-bg` [S01, S06]. |
| `.table-wrap` | Envolve tabelas de dados com `overflow-x:auto`; tabela tem `caption`, `th scope="col/row"` e não é usada para layout. | Em viewport estreita, o deslocamento horizontal fica restrito à tabela; legendas e cabeçalhos anunciam relações [S07, S08]. |
| `code.ref`, `code.ref-file` | Nome completo de método ou caminho de arquivo, com `<wbr>` depois de cada ponto ou barra. Os `<wbr class="ref-camel">` e `<wbr class="ref-long">` dentro de nomes longos ficam inativos, exceto nas células de tabelas de quatro colunas; em telas de até 30rem, `ref-long` vale em todo o texto e `ref-camel`, nas células de tabela. | O nome completo é verificável e quebra em pontos legíveis, sem rolagem horizontal da página e sem partir o identificador onde há espaço [S07]. |
| `.code-block` | `<figure><pre><code>…</code></pre><figcaption>`; código preserva TAB com `tab-size: 4`, pode rolar horizontalmente; `data-source` indica arquivo e linhas, e a legenda cita o método por `code.ref`. | Trecho é figura autocontida com legenda e não força o restante do documento a duas dimensões [S03, S07]. |
| `.keyword` e `mark` | Destaques curtos para nomes de modos, estados ou conceitos críticos; preferir também peso tipográfico, não colorir frases completas. | `mark` indica relevância contextual; uso contido evita ruído para leitores de tela [S04, S10]. |
| `.diagram` | SVG inline dentro de `figure`; incluir `title`, `desc` quando o fluxo for complexo, `aria-labelledby` e legenda em HTML. | O diagrama não depende de imagem remota e sua ideia permanece descrita em texto [S03, S14]. |
| `kbd` | Envolve somente teclas ou combinações que devam ser acionadas, como `Ctrl` + `F`. | Distingue entrada humana de identificador ou código [S05]. |
| `:focus-visible`, `:target` | Foco de teclado recebe contorno ≥3:1; seção acessada por fragmento ganha borda lateral/fundo leve e `scroll-margin-block-start`. | Foco visual atende contraste não textual; fragmento identifica o destino sem JavaScript [S13, S15]. |

O sumário recebe realce CSS para o link cujo fragmento corresponde ao destino atual, usando seletores como `.page-layout:has(#visao-geral:target) #toc a[href="#visao-geral"]`. Em paralelo, `section:target` realça o próprio título como fallback. CSS não detecta com segurança qual seção cruza a viewport durante a rolagem; o indicador muda quando a navegação por link/fragmento muda o alvo [S13, S17].

## Ordem das seções

A abertura vem antes do sumário e do conteúdo. A sequência central replica os títulos do documento de requisitos; cada tema contém, nessa ordem, o requisito, como o módulo atende e o que avaliar na adoção. Não reordenar riscos e decisões, pois dependem das limitações já apresentadas [S01, S10].

| Ordem | Seção HTML | Conteúdo esperado |
| ---: | --- | --- |
| 1 | Abertura | H1, subtítulo, metadados, callout “Natureza deste documento” e bloco “Como ler”. |
| 2 | Sumário | Navegação com links de fragmento para a abertura e cada seção. |
| 3 | Visão geral da ferramenta | RQ-01; operações criar/executar/parar/reverter/excluir com requisito e implementação; avaliação na adoção. |
| 4 | Pontos-chave da execução | RQ-02; percurso da execução e diagrama; avaliação na adoção. |
| 5 | addData, updateData, workflow e versões | RQ-03 e o motivo na plataforma; versões, identidade e workflow; avaliação na adoção. |
| 6 | Campos HTML e widgets embutidos | RQ-04 e o motivo na plataforma; gravação única, rewrite e bloqueio sem mapeamento; avaliação na adoção. |
| 7 | Mídia, documento, categoria e arquivos | RQ-05; tabela de dependências e fingerprint; avaliação na adoção. |
| 8 | Clock, transação, volume e maxRunTime | RQ-06 e o motivo na plataforma; executor, lock, transação por item e volume; avaliação na adoção. |
| 9 | Reversão do que foi migrado | RQ-07; fluxo de undo e dependências; avaliação na adoção. |
| 10 | Incremental e alterações na origem | RQ-08; `ADD_ONLY` e `UPDATE`; avaliação na adoção. |
| 11 | Riscos e recomendações | Síntese; revisão técnica do módulo; tabela de prioridade, tema com `RQ-nn`, recomendação e método; ordem sugerida na adoção. |
| 12 | Decisões e informações necessárias na adoção | Síntese; `AD-01` a `AD-03`, esclarecidas pelo código da plataforma; `AD-04` a `AD-07`, que dependem de cada projeto. |
| 13 | Termos usados | Glossário em tabela simples com termo e significado. |
| 14 | Próximos passos na adoção | Síntese; passos para analisar, testar e adaptar; limite do documento. |

## Abertura

Esqueleto da abertura e função de cada bloco:

1. `<header class="site-header">` com um único `<h1>`: **“Migração de conteúdo entre instâncias — visão geral”**; o `<title>` repete esse nome.
2. Subtítulo imediatamente abaixo: **“Requisitos e comportamento da migração de conteúdo entre instâncias do mesmo serviço LumisXP 19.0.0”**.
3. Metadados em `<dl class="document-meta">`: módulo `br.com.lumis.commons.contentmigrator`, plataforma LumisXP 19.0.0 e público “Equipes de projetos LumisXP”.
4. `<aside class="callout callout--nature" aria-labelledby="natureza-title">` com o título “Natureza deste documento”: escopo da ferramenta, link para o documento de requisitos, os limites declarados em cada tema e a origem das evidências da plataforma (fontes do LumisXP 18.1.1, versão de que o módulo foi portado, não reconferidas na 19.0.0), com o caminho do levantamento interno em `<code>`, sem link.
5. Bloco `<section class="reading-guide" aria-labelledby="como-ler-title">` com o título “Como ler”: a sequência requisito, como o módulo atende e o que avaliar na adoção; a forma das referências de código; os identificadores `RQ-nn` e `AD-nn`.
6. Em seguida, `<aside>` para o sumário recolhível e `<main id="conteudo">` com as seções ordenadas acima.

O título único e as regiões nomeadas suportam navegação semântica; a abertura antecipa o escopo e a forma de leitura sem antecipar a análise de cada tema [S01, S02, S10].

## Responsividade, tema e impressão

- Em `@media (max-width: 48rem)`, uma coluna, padding lateral de `--space-4`, sumário acima do artigo e tabelas/código com rolagem própria. O texto corrido não deve ganhar rolagem horizontal em zoom equivalente a 320 CSS px [S07, S18].
- Em `@media (prefers-color-scheme: dark)`, sobrescrever os tokens escuros da tabela; links, destaques, código, callouts e foco mudam juntos. Não usar imagem de texto ou dependência de fundo externo [S06, S12].
- Em `@media print`, fundo branco e tinta escura; esconder sumário sticky e controles de navegação; retirar sombras, limitar quebras dentro de callouts, figuras e blocos de código; permitir que links e títulos continuem legíveis. `@page` pode definir margens para papel/PDF [S11].
- O CSS permanece inline em `<style>` no documento final; não incluir `@import`, imagem externa, fonte externa ou JavaScript obrigatório; `test/check_html.py` recusa recursos externos e script obrigatório.

## Verificação

1. Recalcular os cinco pares em ambos os temas após qualquer alteração dos tokens; manter cada razão ≥4.5 para 1 e o foco/bordas essenciais ≥3:1 [S06, S15].
2. Usar `test/check_html.py` para estrutura, âncoras, recursos, CSS exigido e os pares `data-contrast-pairs`, e para conferir que `pre[data-source]` reproduz as linhas apontadas.
3. Usar `test/check_code_refs.py` para resolver cada `code.ref` no fonte do módulo ou da plataforma, `test/check_doc_links.py` para os links e `test/check_tone.py` para o tom.
4. Conferir em tela estreita, tema escuro, teclado e impressão; validar a composição em Chrome headless com `test/render_html.sh`, que gera capturas claras e escuras, PDF e a sonda de overflow horizontal.

### Verificação visual

- Uma sonda anterior encontrou `scrollWidth=743` em 390 e 360 px. Rótulos longos de links não quebravam linha; `overflow-wrap: anywhere` na regra global de links eliminou a rolagem horizontal da página.
- Num PDF anterior, linhas longas de código e a tabela de recomendações ultrapassavam a área imprimível, e os diagramas verticais eram divididos entre páginas. As regras de impressão agora quebram linhas em código e células, limitam a altura dos SVGs e mantêm cada diagrama com sua legenda na mesma página.
- A quebra de página também separa títulos de suas explicações. `break-after: avoid-page` mantém cada título, como “O que avaliar na adoção”, junto do texto seguinte.
- Na renderização atual em Chrome headless por `test/render_html.sh`, a sonda passou em 1280, 768, 390 e 360 px (`scrollWidth` igual a `innerWidth` em todas), e o PDF foi gerado. As tabelas estreitas preservam rolagem própria dentro de `.table-wrap`.
