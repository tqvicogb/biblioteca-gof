package biblioteca;

import java.io.Serializable;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

interface PoliticaMulta {
    String nome();
    default String descricao() { return nome(); }
    BigDecimal calcular(long dias);
    default BigDecimal reais(long dias) { return BigDecimal.valueOf(Math.max(0, dias)).setScale(2); }
}
record PoliticaOriginal() implements PoliticaMulta {
    public String nome() { return "original"; }
    public String descricao() { return "Original: R$ 1 por dia"; }
    public BigDecimal calcular(long dias) { return reais(dias); }
}
record PoliticaComTeto() implements PoliticaMulta {
    public String nome() { return "teto"; }
    public String descricao() { return "Teto: máximo de R$ 20"; }
    public BigDecimal calcular(long dias) { return reais(Math.min(20, dias)); }
}
record PoliticaComTolerancia() implements PoliticaMulta {
    public String nome() { return "tolerância"; }
    public String descricao() { return "Tolerância: desconta dois dias do atraso"; }
    public BigDecimal calcular(long dias) { return reais(Math.max(0, dias) - 2); }
}
record OpcaoPolitica(String chave, String descricao) {}
final class ConfiguracaoMulta {
    private final Map<String, PoliticaMulta> politicas = new LinkedHashMap<>();
    private final AtomicReference<PoliticaMulta> vigente;
    ConfiguracaoMulta(PoliticaMulta politica) {
        registrar("original", new PoliticaOriginal());
        registrar("teto", new PoliticaComTeto());
        registrar("tolerancia", new PoliticaComTolerancia());
        vigente = new AtomicReference<>(Objects.requireNonNull(politica));
    }
    synchronized void registrar(String chave, PoliticaMulta politica) {
        RotinaCadastro.exigirTexto(chave);
        Objects.requireNonNull(politica);
        if (politicas.putIfAbsent(chave, politica) != null) throw new IllegalArgumentException("Política já cadastrada");
    }
    synchronized List<OpcaoPolitica> opcoes() {
        return politicas.entrySet().stream().map(e -> new OpcaoPolitica(e.getKey(), e.getValue().descricao())).toList();
    }
    synchronized void escolher(String chave) {
        PoliticaMulta politica = politicas.get(chave);
        if (politica == null) throw new IllegalArgumentException("Política desconhecida");
        escolher(politica);
    }
    PoliticaMulta vigente() { return vigente.get(); }
    void escolher(PoliticaMulta politica) { vigente.set(Objects.requireNonNull(politica)); }
}

enum SituacaoCobranca { ABERTA, QUITADA, DESCONHECIDA }
record SolicitacaoCobranca(UUID id, String cpf, BigDecimal valor) {}
record Cobranca(String referencia, SituacaoCobranca situacao) implements Serializable {}
record ConfirmacaoPagamento(String referencia, String pagamento, SituacaoCobranca situacao) {}
record BoletoDeMulta(UUID emprestimo, BigDecimal valor, String politica, Cobranca cobranca)
        implements Serializable {}

interface iCobranca {
    Cobranca emitir(SolicitacaoCobranca solicitacao);
    default boolean simularPagamento(Cobranca cobranca, String protocolo, iQuitacao financeiro) {
        throw new UnsupportedOperationException("Esta integração não oferece simulação de pagamento");
    }
}
interface iQuitacao { boolean registrarQuitacao(ConfirmacaoPagamento confirmacao); }
interface iMulta { BoletoDeMulta emitirMulta(Devolucao devolucao); }

final class BancoA {
    record Resposta(long nossoNumero, int status) {}
    record Aviso(long nossoNumero, long protocolo, int status) {}
    private long centavos;
    private final UnidadeDeTrabalho unidade;
    private final Repositorio<Serializable> registros;
    BancoA() { this(new FabricaEmMemoria()); }
    private BancoA(FabricaPersistencia fabrica) { this(fabrica.unidade(), fabrica.movimentacoes()); }
    BancoA(UnidadeDeTrabalho unidade, Repositorio<Serializable> registros) {
        this.unidade = unidade;
        this.registros = registros;
    }
    long ultimoValor() { return centavos; }
    Resposta criar(String seuNumero, String cpfSemMascara, long centavos) {
        return unidade.executar(() -> {
            this.centavos = centavos;
            String chave = "simulacao:bancoA:pedido:" + seuNumero;
            Long anterior = (Long) registros.buscar(chave);
            if (anterior != null) return new Resposta(anterior, 10);
            Long sequencia = (Long) registros.buscar("simulacao:bancoA:sequencia");
            long numero = sequencia == null ? 0 : sequencia;
            do { numero = Math.addExact(numero, 1); }
            while (registros.buscar("banco:A:" + numero) != null);
            registros.salvar("simulacao:bancoA:sequencia", numero);
            registros.salvar(chave, numero);
            return new Resposta(numero, 10);
        });
    }
}
record AdaptadorBancoA(BancoA banco) implements iCobranca {
    public boolean simularPagamento(Cobranca cobranca, String protocolo, iQuitacao financeiro) {
        if (!cobranca.referencia().startsWith("A:")) throw new IllegalArgumentException("Cobrança de outro banco");
        return receber(new BancoA.Aviso(Long.parseLong(cobranca.referencia().substring(2)),
                Long.parseLong(protocolo), 20), financeiro);
    }
    public Cobranca emitir(SolicitacaoCobranca s) {
        var resposta = banco.criar(s.id().toString(), RotinaCadastro.cpf(s.cpf()),
                s.valor().movePointRight(2).longValueExact());
        return new Cobranca(referencia(resposta.nossoNumero()), situacao(resposta.status()));
    }
    boolean receber(BancoA.Aviso aviso, iQuitacao financeiro) {
        return financeiro.registrarQuitacao(new ConfirmacaoPagamento(referencia(aviso.nossoNumero()),
                Long.toString(aviso.protocolo()), situacao(aviso.status())));
    }
    private String referencia(long numero) { return "A:" + numero; }
    private SituacaoCobranca situacao(int status) {
        return switch (status) {
            case 10 -> SituacaoCobranca.ABERTA;
            case 20 -> SituacaoCobranca.QUITADA;
            default -> SituacaoCobranca.DESCONHECIDA;
        };
    }
}

final class BancoB {
    record Pedido(UUID identificador, String documento, String reais) {}
    record Resposta(UUID identificador, String status) {}
    record Aviso(UUID identificador, String protocolo, String status) {}
    private String valor;
    String ultimoValor() { return valor; }
    Resposta gerar(Pedido pedido) {
        valor = pedido.reais();
        return new Resposta(pedido.identificador(), "OPEN");
    }
}
record AdaptadorBancoB(BancoB banco) implements iCobranca {
    public boolean simularPagamento(Cobranca cobranca, String protocolo, iQuitacao financeiro) {
        if (!cobranca.referencia().startsWith("B:")) throw new IllegalArgumentException("Cobrança de outro banco");
        return receber(new BancoB.Aviso(UUID.fromString(cobranca.referencia().substring(2)),
                protocolo, "PAID"), financeiro);
    }
    public Cobranca emitir(SolicitacaoCobranca s) {
        String cpf = RotinaCadastro.cpf(s.cpf());
        String documento = cpf.replaceFirst("(\\d{3})(\\d{3})(\\d{3})(\\d{2})", "$1.$2.$3-$4");
        var resposta = banco.gerar(new BancoB.Pedido(s.id(), documento,
                s.valor().setScale(2, RoundingMode.UNNECESSARY).toPlainString().replace('.', ',')));
        return new Cobranca(referencia(resposta.identificador()), situacao(resposta.status()));
    }
    boolean receber(BancoB.Aviso aviso, iQuitacao financeiro) {
        return financeiro.registrarQuitacao(new ConfirmacaoPagamento(referencia(aviso.identificador()),
                aviso.protocolo(), situacao(aviso.status())));
    }
    private String referencia(UUID id) { return "B:" + id; }
    private SituacaoCobranca situacao(String status) {
        return switch (status) {
            case "OPEN" -> SituacaoCobranca.ABERTA;
            case "PAID" -> SituacaoCobranca.QUITADA;
            default -> SituacaoCobranca.DESCONHECIDA;
        };
    }
}

record TratadorDeMulta(UnidadeDeTrabalho unidade, Repositorio<Serializable> registros,
                       ConfiguracaoMulta configuracao, iCobranca banco) implements iMulta, iQuitacao {
    String politicaVigente() { return configuracao.vigente().nome(); }
    List<OpcaoPolitica> politicasDisponiveis() { return configuracao.opcoes(); }
    void alterarPolitica(String nome) { configuracao.escolher(nome); }
    BoletoDeMulta consultarPorLivro(String livro) {
        return unidade.executar(() -> {
            UUID id = (UUID) registros.buscar("ultimo:" + livro);
            return id == null ? null : consultar(id);
        });
    }
    boolean simularPagamento(UUID id, String protocolo) {
        BoletoDeMulta boleto = consultar(id);
        if (boleto == null) throw new IllegalArgumentException("Multa não encontrada para este empréstimo");
        RotinaCadastro.exigirTexto(protocolo);
        return banco.simularPagamento(boleto.cobranca(), protocolo, this);
    }
    public BoletoDeMulta emitirMulta(Devolucao devolucao) {
        return unidade.executar(() -> {
            UUID id = devolucao.emprestimo().id();
            BoletoDeMulta atual = (BoletoDeMulta) registros.buscar("multa:" + id);
            if (atual != null) return atual;
            long dias = ChronoUnit.DAYS.between(devolucao.emprestimo().vencimento(), devolucao.data());
            PoliticaMulta politica = configuracao.vigente();
            BigDecimal valor = politica.calcular(dias);
            if (valor.signum() == 0) return null;
            Cobranca cobranca = banco.emitir(new SolicitacaoCobranca(id, devolucao.estudante().cpf(), valor));
            BoletoDeMulta boleto = new BoletoDeMulta(id, valor, politica.nome(), cobranca);
            registros.salvar("multa:" + id, boleto);
            registros.salvar("banco:" + cobranca.referencia(), id);
            return boleto;
        });
    }

    BoletoDeMulta consultar(UUID id) {
        return unidade.executar(() -> (BoletoDeMulta) registros.buscar("multa:" + id));
    }

    public boolean registrarQuitacao(ConfirmacaoPagamento aviso) {
        if (aviso.situacao() != SituacaoCobranca.QUITADA) return false;
        return unidade.executar(() -> {
            UUID id = (UUID) registros.buscar("banco:" + aviso.referencia());
            if (id == null) return false;
            BoletoDeMulta boleto = (BoletoDeMulta) registros.buscar("multa:" + id);
            if (boleto.cobranca().situacao() != SituacaoCobranca.ABERTA) return false;
            registros.salvar("multa:" + id, new BoletoDeMulta(id, boleto.valor(), boleto.politica(),
                    new Cobranca(aviso.referencia(), SituacaoCobranca.QUITADA)));
            registros.salvar("quitacao:" + id, aviso.pagamento());
            return true;
        });
    }
}
