# Migração hierárquica (AGENTS)

- Seleção inclui descendentes calculados a cada execução; não congele a árvore selecionada como lista definitiva de itens.
- Preserve o isolamento da persistência deste serviço e a proteção contra migração integral concorrente para o mesmo destino.
- Não acrescente imports do serviço irmão para reaproveitar código; mantenha a autonomia dos serviços até uma extração compartilhada explicitamente planejada.
