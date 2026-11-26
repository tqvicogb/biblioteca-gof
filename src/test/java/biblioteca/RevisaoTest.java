package biblioteca;

import org.junit.Test;
import static org.junit.Assert.*;
import java.io.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Regressões da revisão do menu e das regras, além dos dez cenários originais. */
public class RevisaoTest {
    private static final String CPF = "12345678909";
    private static final LocalDate PRAZO = LocalDate.of(2026, 12, 10);

    private Biblioteca nova(boolean cache, iCobranca banco) {
        var b = new Biblioteca(new FabricaEmMemoria(), new PoliticaOriginal(), banco, texto -> {}, cache, true);
        b.pessoas.incluir(new Pessoa(CPF, "Ana", "ana@example.org"));
        b.estudantes.incluir(new Estudante("E1", CPF));
        b.livros.incluir(new Livro("L1", "Título antigo"));
        return b;
    }
    private Biblioteca nova() { return nova(false, new AdaptadorBancoA(new BancoA())); }
    private Comprovante emprestar(Biblioteca b) {
        b.emprestimos.solicitar(new SolicitarReserva(UUID.randomUUID(), "E1", Instant.now(), "L1"));
        return b.emprestimos.emprestar("L1", "E1", PRAZO);
    }
    private Comprovante renovar(Biblioteca b) {
        return b.emprestimos.solicitar(new SolicitarRenovacao(UUID.randomUUID(), "E1", Instant.now(), "L1"));
    }

    @Test public void estadoProduzPrazoEContagemSemMutarOriginal() {
        var anterior = new Emprestimo(UUID.randomUUID(), "L1", "E1", PRAZO, 0);
        var livro = new Livro("L1", "Java", new Emprestado());
        ResultadoRenovacao r = livro.renovar(anterior);
        assertEquals("RENOVADO", r.livro().estado().codigo());
        assertEquals(PRAZO.plusDays(30), r.emprestimo().vencimento());
        assertEquals(1, r.emprestimo().renovacoes());
        assertEquals(0, anterior.renovacoes());
        assertEquals("EMPRESTADO", livro.estado().codigo());
    }

    @Test public void consultaMostraPrazoPreservadoEHistoricoAposDevolucao() {
        var b = nova();
        var id = emprestar(b).id();
        renovar(b); renovar(b);
        SituacaoLivro antes = b.emprestimos.consultarSituacao("L1");
        assertThrows(IllegalStateException.class, () -> renovar(b));
        assertEquals(antes, b.emprestimos.consultarSituacao("L1"));
        b.devolucoes.devolver("L1", "E1", PRAZO.plusDays(65));
        SituacaoLivro depois = b.emprestimos.consultarSituacao("L1");
        assertEquals("DEVOLVIDO", depois.estado());
        assertNull(depois.emprestimo());
        assertEquals(id, depois.ultimoEmprestimo());
        assertEquals(new BigDecimal("5.00"), b.multas.consultarPorLivro("L1").valor());
    }

    @Test public void bancoANaoConfundeUUIDsComMesmoHash() {
        UUID um = UUID.fromString("99837563-0e7b-df2a-0891-2bf43ddbb8da");
        UUID dois = UUID.fromString("a379dee5-61cc-bacd-d13c-76bd1178c418");
        assertEquals(um.toString().hashCode(), dois.toString().hashCode());
        var banco = new AdaptadorBancoA(new BancoA());
        var pedido = new SolicitacaoCobranca(um, CPF, new BigDecimal("7.00"));
        Cobranca a = banco.emitir(pedido);
        Cobranca b = banco.emitir(new SolicitacaoCobranca(dois, CPF, new BigDecimal("7.00")));
        assertNotEquals(a.referencia(), b.referencia());
        assertEquals(a, banco.emitir(pedido));
    }

    @Test public void sequenciaBancoASobreviveReconstrucaoDaSimulacao() {
        var fabrica = new FabricaEmMemoria();
        fabrica.unidade().executar(() -> { fabrica.movimentacoes().salvar("banco:A:1", UUID.randomUUID()); return null; });
        var banco = new BancoA(fabrica.unidade(), fabrica.movimentacoes());
        long primeiro = banco.criar("pedido-um", CPF, 700).nossoNumero();
        assertEquals(2L, primeiro); // preserva uma referência legada já ocupada
        var reconstruido = new BancoA(fabrica.unidade(), fabrica.movimentacoes());
        assertEquals(primeiro, reconstruido.criar("pedido-um", CPF, 700).nossoNumero());
        assertTrue(reconstruido.criar("pedido-dois", CPF, 700).nossoNumero() > primeiro);
    }

    @Test public void cacheInvalidaExclusaoERecadastroComMedicaoExterna() {
        var b = nova(true, new AdaptadorBancoA(new BancoA()));
        assertEquals("Título antigo", b.livros.consultar("L1").titulo());
        b.livros.excluir("L1");
        assertNull(b.livros.consultar("L1"));
        b.livros.incluir(new Livro("L1", "Título novo"));
        assertEquals("Título novo", b.livros.consultar("L1").titulo());
        assertEquals("Título novo", b.livros.consultar("L1").titulo());
    }

    @Test public void exclusaoRecusadaPreservaConsultaEEstado() {
        var b = nova(true, new AdaptadorBancoA(new BancoA()));
        emprestar(b);
        assertThrows(IllegalStateException.class, () -> b.livros.excluir("L1"));
        assertEquals("Título antigo", b.livros.consultar("L1").titulo());
        assertEquals("EMPRESTADO", b.emprestimos.consultarSituacao("L1").estado());
    }

    @Test public void cacheQueFalhaAoInvalidarNaoDevolveEntradaAntiga() {
        var valor = new AtomicReference<>(new Metadados("X", "Antigo"));
        ConcurrentMap<String, Metadados> falho = new ConcurrentHashMap<>() {
            @Override public Metadados remove(Object chave) { throw new IllegalStateException("Cache indisponível"); }
        };
        var cache = new ConsultaComCache(id -> valor.get(), falho);
        assertEquals("Antigo", cache.consultar("X").titulo());
        valor.set(new Metadados("X", "Novo"));
        cache.invalidar("X");
        assertEquals("Novo", cache.consultar("X").titulo());
    }

    @Test public void leituraConcorrenteAntigaNaoRepovoaCacheInvalidado() throws Exception {
        var iniciou = new CountDownLatch(1);
        var continuar = new CountDownLatch(1);
        var chamadas = new AtomicInteger();
        var cache = new ConsultaComCache(id -> {
            if (chamadas.incrementAndGet() == 1) {
                iniciou.countDown();
                try { if (!continuar.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Timeout"); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
                return new Metadados(id, "Antigo");
            }
            return new Metadados(id, "Novo");
        });
        var pool = Executors.newSingleThreadExecutor();
        try {
            var pendente = pool.submit(() -> cache.consultar("X"));
            assertTrue(iniciou.await(5, TimeUnit.SECONDS));
            cache.invalidar("X");
            continuar.countDown();
            assertEquals("Antigo", pendente.get(5, TimeUnit.SECONDS).titulo());
            assertEquals("Novo", cache.consultar("X").titulo());
            assertEquals("Novo", cache.consultar("X").titulo());
            assertEquals(2, chamadas.get());
        } finally { continuar.countDown(); pool.shutdownNow(); }
    }

    @Test public void trocaPeloTratadorSoAfetaMultasNovas() {
        var b = nova();
        UUID anterior = emprestar(b).id();
        b.devolucoes.devolver("L1", "E1", PRAZO.plusDays(5));
        b.multas.alterarPolitica("tolerancia");
        assertEquals("tolerância", b.multas.politicaVigente());
        assertEquals(new BigDecimal("5.00"), b.multas.consultar(anterior).valor());
        b.devolucoes.inspecionar("L1", false);
        emprestar(b);
        b.devolucoes.devolver("L1", "E1", PRAZO.plusDays(5));
        assertEquals(new BigDecimal("3.00"), b.multas.consultarPorLivro("L1").valor());
        assertEquals("original", b.multas.consultar(anterior).politica());
        assertThrows(IllegalArgumentException.class, () -> b.multas.alterarPolitica("inexistente"));
        assertEquals("tolerância", b.multas.politicaVigente());
    }

    @Test public void pagamentoSimuladoDeAmbosBancosEhIdempotente() {
        for (iCobranca banco : List.of(new AdaptadorBancoA(new BancoA()), new AdaptadorBancoB(new BancoB()))) {
            var b = nova(false, banco);
            UUID id = emprestar(b).id();
            b.devolucoes.devolver("L1", "E1", PRAZO.plusDays(5));
            assertTrue(b.multas.simularPagamento(id, "123"));
            assertFalse(b.multas.simularPagamento(id, "123"));
            assertFalse(b.multas.simularPagamento(id, "456"));
            assertEquals(SituacaoCobranca.QUITADA, b.multas.consultar(id).cobranca().situacao());
        }
    }

    @Test public void filaSoRemoveDepoisDaInspecaoBemSucedida() {
        var b = nova();
        emprestar(b);
        assertThrows(IllegalStateException.class, () -> b.devolucoes.inspecionar("L1", false));
        b.devolucoes.devolver("L1", "E1", PRAZO);
        assertEquals(List.of("L1"), b.devolucoes.pendentes());
        assertThrows(IllegalArgumentException.class, () -> b.devolucoes.inspecionar("inexistente", false));
        assertEquals(List.of("L1"), b.devolucoes.pendentes());
        b.devolucoes.inspecionar("L1", true);
        assertTrue(b.devolucoes.pendentes().isEmpty());
        assertEquals("REPARANDO", b.emprestimos.consultarSituacao("L1").estado());
        assertEquals(1, b.relatorios.consultar().total());
    }

    @Test public void hierarquiaMostraSubsecoesEMovimentacaoPreservaLivro() {
        var b = nova();
        emprestar(b);
        var antes = b.emprestimos.consultarSituacao("L1");
        b.organizacao.criarGrupo("raiz", "A");
        b.organizacao.criarGrupo("A", "A1");
        b.organizacao.criarGrupo("raiz", "B");
        b.organizacao.incluirLivro("A1", "L1");
        assertEquals(1, b.organizacao.contar("raiz"));
        assertTrue(b.organizacao.hierarquia().contains(new EntradaAcervo("L1", 3, false, 1)));
        b.organizacao.mover("A1", "B", "L1");
        assertEquals(antes, b.emprestimos.consultarSituacao("L1"));
        assertEquals(List.of("L1"), b.organizacao.listar("B"));
    }

    @Test public void menuExecutaFluxoCompletoComRecusasEPagamento() throws Exception {
        var b = nova();
        String roteiro = String.join("\n", "3", "1", "L1", "E1", "2", "L1", "E1", "10/12/2026",
                "3", "L1", "E1", "4", "3", "L1", "E1", "3", "L1", "E1", "5", "L1", "0",
                "4", "1", "L1", "E1", "13/02/2027", "7", "0",
                "5", "3", "L1", "2", "3", "3", "L1", "5", "L1", "6", "0",
                "3", "1", "L1", "E1", "0", "4", "2", "L1", "7", "0", "0") + "\n";
        var buffer = new ByteArrayOutputStream();
        new Main(b, new Scanner(roteiro), new PrintStream(buffer, true, "UTF-8")).executarMenu();
        String texto = buffer.toString("UTF-8");
        assertTrue(texto.contains("Limite de duas renovações"));
        assertTrue(texto.contains("Renovações realizadas: 2"));
        assertTrue(texto.contains("Vencimento: 08/02/2027"));
        assertTrue(texto.contains("Valor: R$ 5,00"));
        assertTrue(texto.contains("Nenhuma segunda quitação"));
        assertTrue(texto.contains("Operação indisponível no estado DEVOLVIDO"));
        assertTrue(texto.contains("Nenhum livro na fila"));
        assertFalse(texto.contains("Comprovante["));
        assertEquals("LIVRE", b.emprestimos.consultarSituacao("L1").estado());
        assertEquals(SituacaoCobranca.QUITADA, b.multas.consultarPorLivro("L1").cobranca().situacao());
    }

    @Test public void menuCancelaCamposEAceitaOperacaoDepoisDeErro() throws Exception {
        var b = nova();
        var buffer = new ByteArrayOutputStream();
        String roteiro = "99\n1\n4\nL2\nvoltar\n4\nL2\nNovo livro\n0\n3\n2\nL2\nE1\n31/02/2027\n0\n0\n";
        new Main(b, new Scanner(roteiro), new PrintStream(buffer, true, "UTF-8")).executarMenu();
        String texto = buffer.toString("UTF-8");
        assertTrue(texto.contains("Operação cancelada"));
        assertTrue(texto.contains("Data inválida"));
        assertEquals("Novo livro", b.livros.consultar("L2").titulo());
        assertEquals("LIVRE", b.emprestimos.consultarSituacao("L2").estado());
    }
}
