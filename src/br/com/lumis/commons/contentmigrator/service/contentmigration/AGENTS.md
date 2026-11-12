# Migração integral (AGENTS)

- Não use este serviço em instâncias hierárquicas que já têm migração hierárquica: a proteção entre os dois serviços é assimétrica.
- Preserve as dependências dos repositórios até decidir o destino dos clones; mudar dependências depois pode impedir a limpeza no desfazer.
- Excluir preserva conteúdos do destino; recriar o mesmo par pode duplicá-los. Não trate exclusão como desfazer.
