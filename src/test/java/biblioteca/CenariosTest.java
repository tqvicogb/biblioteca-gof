package biblioteca;

import static org.junit.Assert.*;
import org.junit.Test;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class CenariosTest {
    private static final String CPF = "12345678909";
    private static final LocalDate PRAZO = LocalDate.of(2026, 11, 10);

    private Biblioteca biblioteca() {
        var b = new Biblioteca(new FabricaEmMemoria(), new PoliticaOriginal(),
                new AdaptadorBancoA(new BancoA()), texto -> {}, false, false);
        b.pessoas.incluir(new Pessoa(CPF, "Ana", "ana@example.org"));
        b.estudantes.incluir(new Estudante("E1", CPF));
        b.livros.incluir(new Livro("L1", "UML"));
        return b;
    }

    private void reservar(Biblioteca b, String livro) {
        b.emprestimos.solicitar(new SolicitarReserva(UUID.randomUUID(), "E1", Instant.now(), livro));
    }
    private Comprovante emprestar(Biblioteca b, String livro) {
        reservar(b, livro);
        return b.emprestimos.emprestar(livro, "E1", PRAZO);
    }
    private SolicitacaoCirculacao renovacao() {
        return new SolicitarRenovacao(UUID.randomUUID(), "E1", Instant.now(), "L1");
    }
    private Biblioteca biblioteca(FabricaPersistencia fabrica) {
        return new Biblioteca(fabrica, new PoliticaOriginal(), new AdaptadorBancoA(new BancoA()),
                texto -> {}, false, false);
    }
    private Livro livro(Biblioteca b) {
        return b.persistencia.unidade().executar(() -> b.acervo.exigir("L1"));
    }
    private Emprestimo ativo(Biblioteca b) {
        return b.persistencia.unidade().executar(() -> b.movimentacao.ativo("L1"));
    }

    @Test public void R01_terceiraRenovacaoRecusada() {
        var b = biblioteca();
        emprestar(b, "L1");
        b.emprestimos.solicitar(renovacao());
        assertEquals(PRAZO.plusDays(60), b.emprestimos.solicitar(renovacao()).vencimento());
        assertThrows(IllegalStateException.class, () -> b.emprestimos.solicitar(renovacao()));
        assertEquals(PRAZO.plusDays(60), ativo(b).vencimento());
        b.devolucoes.devolver("L1", "E1", PRAZO);
        assertThrows(IllegalStateException.class, () -> reservar(b, "L1"));
        b.devolucoes.inspecionar("L1", true);
        assertEquals("INCINERADO", b.devolucoes.laudoIrrecuperavel("L1").estado());
        assertThrows(IllegalStateException.class, () -> reservar(b, "L1"));
    }

    @Test public void R02_politicasDeMulta() {
        var b = biblioteca();
        assertEquals(new BigDecimal("5.00"), new PoliticaOriginal().calcular(5));
        assertEquals(new BigDecimal("3.00"), new PoliticaComTolerancia().calcular(5));
        assertEquals(new BigDecimal("20.00"), new PoliticaComTeto().calcular(31));
        assertEquals(new BigDecimal("0.00"), new PoliticaComTolerancia().calcular(0));
        var emprestimo = emprestar(b, "L1");
        b.devolucoes.devolver("L1", "E1", PRAZO.plusDays(5));
        b.configuracaoMulta.escolher(new PoliticaComTolerancia());
        assertEquals(new BigDecimal("5.00"), b.multas.consultar(emprestimo.id()).valor());
        assertEquals("original", b.multas.consultar(emprestimo.id()).politica());
        b.devolucoes.inspecionar("L1", false);
        var segundo = emprestar(b, "L1");
        b.devolucoes.devolver("L1", "E1", PRAZO.plusDays(5));
        assertEquals(new BigDecimal("3.00"), b.multas.consultar(segundo.id()).valor());
    }

    @Test public void R03_adaptaValoresEImpedeQuitacaoDuplicada() {
        var a = new BancoA();
        var b = new BancoB();
        var adaptadorA = new AdaptadorBancoA(a);
        var adaptadorB = new AdaptadorBancoB(b);
        var solicitacao = new SolicitacaoCobranca(UUID.randomUUID(), CPF, new BigDecimal("7.00"));
        adaptadorA.emitir(solicitacao);
        adaptadorB.emitir(solicitacao);
        assertEquals(700L, a.ultimoValor());
        assertEquals("7,00", b.ultimoValor());

        var fabrica = new FabricaEmMemoria();
        var multas = new TratadorDeMulta(fabrica.unidade(), fabrica.movimentacoes(),
                new ConfiguracaoMulta(new PoliticaOriginal()), adaptadorA);
        var emprestimo = new Emprestimo(UUID.randomUUID(), "L1", "E1", PRAZO, 0);
        var boleto = multas.emitirMulta(new Devolucao(emprestimo, new Estudante("E1", CPF),
                new Pessoa(CPF, "Ana", "ana@example.org"), PRAZO.plusDays(7)));
        long numero = Long.parseLong(boleto.cobranca().referencia().substring(2));
        var aviso = new BancoA.Aviso(numero, 100L, 20);
        assertTrue(adaptadorA.receber(aviso, multas));
        assertFalse(adaptadorA.receber(aviso, multas));
        assertEquals(SituacaoCobranca.QUITADA, multas.consultar(emprestimo.id()).cobranca().situacao());
    }

    @Test public void R04_fachadaEfetivaEmprestimoEmUmaTransacao() {
        var b = biblioteca();
        assertThrows(IllegalStateException.class, () -> b.emprestimos.emprestar("L1", "E1", PRAZO));
        assertEquals("LIVRE", livro(b).estado().codigo());
        reservar(b, "L1");
        var fabrica = b.persistencia;
        var repositorio = fabrica.livros();
        Repositorio<RegistroLivro> falhaNaGravacao = new Repositorio<>() {
            public RegistroLivro buscar(String id) { return repositorio.buscar(id); }
            public void salvar(String id, RegistroLivro valor) { throw new IllegalStateException("falha simulada"); }
            public void remover(String id) { repositorio.remover(id); }
        };
        var fachada = new FachadaCirculacao(fabrica.unidade(), new Acervo(falhaNaGravacao),
                b.movimentacao, b.relacionamento, b.publicador);
        assertThrows(IllegalStateException.class, () -> fachada.emprestar("L1", "E1", PRAZO));
        assertNotNull(b.emprestimos.consultarReserva("L1", "E1"));
        assertNull(ativo(b));
        assertEquals("RESERVADO", livro(b).estado().codigo());

        var comprovante = b.emprestimos.emprestar("L1", "E1", PRAZO);
        assertEquals("EMPRESTADO", comprovante.estado());
        assertEquals(comprovante.id(), ativo(b).id());
        assertNull(b.emprestimos.consultarReserva("L1", "E1"));
    }

    @Test public void R05_publicaDepoisDeRegistrarEIsolaFalhas() {
        var b = biblioteca();
        b.publicador.registrar(evento -> { throw new IllegalStateException("e-mail indisponível"); });
        var executado = new AtomicInteger();
        b.publicador.registrar(evento -> {
            assertNull(ativo(b));
            assertEquals("DEVOLVIDO", livro(b).estado().codigo());
            executado.incrementAndGet();
        });
        assertThrows(IllegalStateException.class, () -> b.devolucoes.devolver("L1", "E1", PRAZO));
        assertEquals(0, b.indicadores.total());
        var atrasado = emprestar(b, "L1");
        b.devolucoes.devolver("L1", "E1", PRAZO.plusDays(5));
        assertEquals(List.of("L1"), b.inspecao.fila());
        assertEquals(1, b.indicadores.total());
        assertNotNull(b.multas.consultar(atrasado.id()));
        assertEquals(1, executado.get());
        assertEquals(1, b.publicador.falhas().size());
        assertNotNull(b.persistencia.unidade().executar(() -> b.movimentacao.devolucao(atrasado.id())));
        b.devolucoes.inspecionar("L1", false);
        var pontual = emprestar(b, "L1");
        b.devolucoes.devolver("L1", "E1", PRAZO);
        assertNull(b.multas.consultar(pontual.id()));
    }

    @Test public void R06_mesmoIdentificadorMesmoResultadoEmConcorrencia() throws Exception {
        var b = biblioteca();
        emprestar(b, "L1");
        var comando = renovacao();
        var inicio = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            Callable<Comprovante> tentativa = () -> {
                inicio.await();
                return b.emprestimos.solicitar(comando);
            };
            var primeira = pool.submit(tentativa);
            var segunda = pool.submit(tentativa);
            inicio.countDown();
            assertEquals(primeira.get(5, TimeUnit.SECONDS), segunda.get(5, TimeUnit.SECONDS));
            assertEquals(PRAZO.plusDays(30), ativo(b).vencimento());
        } finally { pool.shutdownNow(); }
        b.emprestimos.solicitar(renovacao());
        assertThrows(IllegalStateException.class, () -> b.emprestimos.solicitar(renovacao()));
    }

    @Test public void R07_duplicadoEPreRequisitoDeEstudante() {
        var b = biblioteca();
        var duplicado = b.estudantes.incluir(new Estudante("E1", "00000000000"));
        assertTrue(duplicado.existente());
        assertEquals(CPF, duplicado.cadastro().cpf());
        assertThrows(IllegalArgumentException.class,
                () -> b.estudantes.incluir(new Estudante("E2", "00000000000")));
        assertNull(b.persistencia.unidade().executar(() -> b.relacionamento.estudante("E2")));
    }

    @Test public void R08_perfisCriamFamiliasCompativeis() {
        var memoria = Biblioteca.fabricaPara(Map.of());
        assertTrue(memoria.unidade() instanceof UnidadeEmMemoria);
        for (var repo : List.of(memoria.livros(), memoria.movimentacoes(), memoria.relacionamentos())) {
            assertSame(memoria.unidade(), ((RepositorioEmMemoria<?>) repo).unidade());
        }
        var mysql = Biblioteca.fabricaPara(Map.of("BIB_PERFIL", "producao",
                "BIB_JDBC", "jdbc:mysql://localhost/biblioteca"));
        assertTrue(mysql.unidade() instanceof UnidadeMySQL);
        for (var repo : List.of(mysql.livros(), mysql.movimentacoes(), mysql.relacionamentos())) {
            assertSame(mysql.unidade(), ((RepositorioMySQL<?>) repo).unidade());
        }
        String url = System.getenv("BIB_TESTE_JDBC");
        if (url != null) {
            var b = biblioteca(new FabricaMySQL(url, System.getenv("BIB_USUARIO"), System.getenv("BIB_SENHA")));
            String id = UUID.randomUUID().toString();
            b.livros.incluir(new Livro(id, "JDBC"));
            assertEquals(id, b.livros.consultar(id).id());
            b.livros.excluir(id);
        }
    }

    @Test public void R09_cacheMedeAcertosEMantemEstadoAutoritativo() {
        var b = biblioteca();
        var original = new ConsultaOriginal(b.persistencia.unidade(), b.acervo);
        var leituras = new AtomicInteger();
        var medicoes = new AtomicInteger();
        ConsultaAcervo consulta = new ConsultaComMedicao(new ConsultaComCache(id -> {
            leituras.incrementAndGet();
            return original.consultar(id);
        }), tempo -> medicoes.incrementAndGet());
        assertEquals("UML", consulta.consultar("L1").titulo());
        reservar(b, "L1");
        assertEquals("UML", consulta.consultar("L1").titulo());
        assertEquals(1, leituras.get());
        assertEquals(2, medicoes.get());
        assertThrows(IllegalStateException.class, () -> reservar(b, "L1"));
    }

    @Test public void R10_contaCincoEMoveSemAlterarEmprestimo() {
        var b = biblioteca();
        var unidade = new GrupoAcervo("Unidade");
        var secaoA = new GrupoAcervo("A");
        var secaoB = new GrupoAcervo("B");
        unidade.adicionar(secaoA);
        unidade.adicionar(secaoB);
        for (int i = 1; i <= 3; i++) secaoA.adicionar(new ItemAcervo("L" + i));
        for (int i = 4; i <= 5; i++) secaoB.adicionar(new ItemAcervo("L" + i));
        assertEquals(5, unidade.contar());
        assertThrows(IllegalArgumentException.class, () -> secaoA.adicionar(unidade));
        assertThrows(IllegalArgumentException.class, () -> secaoB.adicionar(new ItemAcervo("L1")));
        emprestar(b, "L1");
        var emprestimoAnterior = ativo(b);
        secaoA.mover("L1", secaoB);
        assertEquals(5, unidade.contar());
        assertEquals(List.of("L4", "L5", "L1"), secaoB.listar());
        assertEquals("L1", livro(b).id());
        assertEquals("EMPRESTADO", livro(b).estado().codigo());
        assertEquals(emprestimoAnterior, ativo(b));
    }
}
