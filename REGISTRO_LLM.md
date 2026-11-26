# Registro de uso de LLM

**Ferramentas:** ChatGPT e ambiente Codex, da OpenAI. **Modelos:** as versões exatas utilizadas nas etapas do projeto ainda precisam ser confirmadas pela equipe.

## Pedidos e decisões

| Pedido | Resultado e decisão |
|---|---|
| Interpretar o enunciado e os requisitos GoF | Incorporados os dez requisitos, os cenários de teste e a relação entre padrões e classes. |
| Gerar a implementação do trabalho | Incorporados código Java, testes JUnit, interface de comandos, persistência e documentação. O registro da etapa anterior também relata apoio na elaboração dos modelos Astah/PDF. |
| Revisar cadastros e circulação | Descartado o cadastro automático dos dois papéis em favor de operações explícitas; corrigida a remoção física do empréstimo para preservar o histórico; emissão de multa atribuída ao ouvinte do Financeiro. |
| Implementar a interface web e configurar MySQL | Incorporados HTML/CSS/JavaScript, backend Java chamando os tratadores e preparação de uma base dedicada. |
| Organizar o terminal para apresentação | Aceito o menu numerado, com campos guiados, consultas, troca de política, pagamento simulado e hierarquia. O modo antigo foi preservado. |
| Corrigir problemas e testar a revisão | Incorporadas correções nas referências do banco A, cache, fila de inspeção e renovação pelos estados; acrescentados 14 testes aos dez originais. |
| Preservar os diagramas anteriores | Não foram alterados Astah/PDF na revisão do menu. As principais mudanças de implementação foram resumidas no README, conforme orientação da equipe. |
| Enxugar a documentação | Descartada a versão extensa do README a pedido da equipe; mantidas instruções essenciais, resumo das alterações e declaração de IA. |
| Alinhar a implementação ao enunciado, sem novas funcionalidades | Guardas de circulação movidas para State; seleção de políticas delegada à configuração; corrigidos vínculos após exclusão/recadastro, persistência dos grupos e conciliação da fila de inspeção. |
| Verificar as correções antes de entregar | Acrescentados 11 testes de alinhamento e um teste de integração. A revisão executou `mvn clean verify` com MySQL real: 36 testes aprovados e `BUILD SUCCESS`. |

## Revisão da equipe

A equipe realizou a revisão funcional em 25/11/2026: executou `mvn clean verify`, com 24 testes aprovados, e verificou o fluxo no terminal, a troca de política sem alterar multas anteriores, a inspeção, a quitação sem duplicidade, o pré-requisito de cadastro e a organização do acervo. Também confirmou no MySQL a recuperação do livro, do vínculo ao empréstimo e da multa após reiniciar o programa.

Na revisão funcional da equipe, foram aceitos o menu organizado e as correções então testadas. A equipe solicitou preservar os diagramas anteriores e descartou a versão extensa do README, mantendo a documentação essencial.

A equipe é responsável por compreender e explicar o código, incluindo as partes geradas ou revisadas com IA. A identificação exata dos modelos utilizados permanece pendente antes da entrega.
