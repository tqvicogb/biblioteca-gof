package biblioteca;

import java.util.Map;
import java.util.function.Consumer;

final class Biblioteca {
    final FabricaPersistencia persistencia;
    final Acervo acervo;
    final Movimentacao movimentacao;
    final Relacionamento relacionamento;
    final PublicadorDevolucao publicador = new PublicadorDevolucao();
    final OuvinteInspecao inspecao;
    final OuvinteIndicadores indicadores = new OuvinteIndicadores();
    final ConfiguracaoMulta configuracaoMulta;
    final TratadorDeMulta multas;
    final TratadorDeAcervo livros;
    final TratadorDePessoa pessoas;
    final TratadorDeEstudante estudantes;
    final TratadorDeAtendente atendentes;
    final TratadorDeEmprestimo emprestimos;
    final TratadorDeDevolucao devolucoes;
    final TratadorDaOrganizacao organizacao;
    final TratadorDeIndicadores relatorios;

    Biblioteca(FabricaPersistencia fabrica, PoliticaMulta politica, iCobranca banco,
               Consumer<String> email, boolean cache, boolean medir) {
        this(fabrica, new ConfiguracaoMulta(politica), banco, email, cache, medir);
    }

    Biblioteca(FabricaPersistencia fabrica, ConfiguracaoMulta politicas, iCobranca banco,
               Consumer<String> email, boolean cache, boolean medir) {
        persistencia = fabrica;
        var unidade = fabrica.unidade();
        acervo = new Acervo(fabrica.livros());
        movimentacao = new Movimentacao(fabrica.movimentacoes());
        relacionamento = new Relacionamento(fabrica.relacionamentos());
        configuracaoMulta = politicas;
        inspecao = new OuvinteInspecao(unidade, acervo);
        var estrutura = new OrganizacaoAcervo(fabrica.movimentacoes());
        multas = new TratadorDeMulta(unidade, fabrica.movimentacoes(), configuracaoMulta, banco);
        publicador.registrar(inspecao);
        publicador.registrar(new OuvinteMulta(multas));
        publicador.registrar(indicadores);
        publicador.registrar(new OuvinteEmail(email));

        ConsultaAcervo consulta = new ConsultaOriginal(unidade, acervo);
        if (cache) consulta = new ConsultaComCache(consulta);
        if (medir) consulta = new ConsultaComMedicao(consulta,
                nanos -> System.err.println("Consulta ao acervo: " + nanos + " ns"));
        livros = new TratadorDeAcervo(unidade, acervo, consulta, movimentacao, estrutura);
        pessoas = new TratadorDePessoa(unidade, relacionamento);
        estudantes = new TratadorDeEstudante(unidade, relacionamento);
        atendentes = new TratadorDeAtendente(unidade, relacionamento);
        organizacao = new TratadorDaOrganizacao(unidade, acervo, estrutura);

        var fachada = new FachadaCirculacao(unidade, acervo, movimentacao, relacionamento, publicador);
        var executor = new ExecutorSolicitacoes(unidade, fabrica.movimentacoes(), fachada);
        emprestimos = new TratadorDeEmprestimo(fachada, executor);
        devolucoes = new TratadorDeDevolucao(fachada, inspecao);
        relatorios = new TratadorDeIndicadores(indicadores, publicador);
    }

    static FabricaPersistencia fabricaPara(Map<String, String> config) {
        return switch (config.getOrDefault("BIB_PERFIL", "memoria")) {
            case "memoria" -> new FabricaEmMemoria();
            case "producao" -> new FabricaMySQL(config.get("BIB_JDBC"),
                    config.getOrDefault("BIB_USUARIO", ""), config.getOrDefault("BIB_SENHA", ""));
            default -> throw new IllegalArgumentException("Perfil desconhecido");
        };
    }

    static Biblioteca configurar(Map<String, String> config) {
        FabricaPersistencia fabrica = fabricaPara(config);
        var politicas = new ConfiguracaoMulta(new PoliticaOriginal());
        politicas.escolher(config.getOrDefault("BIB_MULTA", "original"));
        iCobranca banco = switch (config.getOrDefault("BIB_BANCO", "A")) {
            case "A" -> new AdaptadorBancoA(new BancoA(fabrica.unidade(), fabrica.movimentacoes()));
            case "B" -> new AdaptadorBancoB(new BancoB());
            default -> throw new IllegalArgumentException("Banco desconhecido");
        };
        return new Biblioteca(fabrica, politicas, banco,
                texto -> System.out.println("[e-mail simulado] " + texto),
                Boolean.parseBoolean(config.get("BIB_CACHE")),
                Boolean.parseBoolean(config.get("BIB_MEDIR")));
    }
}
