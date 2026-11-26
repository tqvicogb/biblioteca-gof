package biblioteca;

import org.junit.Test;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;

/** Usa uma base de teste com schema.sql e as credenciais BIB_USUARIO/BIB_SENHA. */
public class IntegracaoMySQLTest {
    private Biblioteca abrir(String url) {
        return Biblioteca.configurar(Map.of("BIB_PERFIL", "producao", "BIB_JDBC", url,
                "BIB_USUARIO", System.getenv().getOrDefault("BIB_USUARIO", ""),
                "BIB_SENHA", System.getenv().getOrDefault("BIB_SENHA", "")));
    }

    @Test public void mysqlPreservaCirculacaoGruposEInspecaoEntreInstancias() {
        String url = System.getenv("BIB_TESTE_JDBC");
        assumeTrue("Defina BIB_TESTE_JDBC para executar com MySQL real", url != null && !url.isBlank());
        String id = "TESTE-" + UUID.randomUUID();
        Biblioteca b = abrir(url);
        String cpf = "12345678909";
        Serializable anterior = b.persistencia.unidade().executar(() -> b.persistencia.movimentacoes().buscar("organizacao:arvore"));
        b.pessoas.incluir(new Pessoa(cpf, "Teste JDBC", "teste@example.org"));
        b.estudantes.incluir(new Estudante(id, cpf));
        b.livros.incluir(new Livro(id, "Livro JDBC"));
        b.organizacao.criarGrupo("raiz", id);
        b.organizacao.incluirLivro(id, id);
        b.emprestimos.solicitar(new SolicitarReserva(UUID.randomUUID(), id, Instant.now(), id));
        var emprestimo = b.emprestimos.emprestar(id, id, LocalDate.of(2026, 12, 10));
        var pedido = new SolicitarRenovacao(UUID.randomUUID(), id, Instant.now(), id);
        var comprovante = b.emprestimos.solicitar(pedido);
        assertEquals(LocalDate.of(2027, 1, 9), comprovante.vencimento());

        Biblioteca segunda = abrir(url);
        assertEquals(comprovante, segunda.emprestimos.solicitar(pedido));
        assertEquals(1, segunda.emprestimos.consultarSituacao(id).emprestimo().renovacoes());
        assertEquals(List.of(id), segunda.organizacao.listar(id));
        segunda.devolucoes.devolver(id, id, LocalDate.of(2027, 1, 14));

        Biblioteca terceira = abrir(url);
        assertEquals("DEVOLVIDO", terceira.emprestimos.consultarSituacao(id).estado());
        assertTrue(terceira.devolucoes.pendentes().contains(id));
        assertEquals(new BigDecimal("5.00"), terceira.multas.consultarPorLivro(id).valor());
        assertTrue(terceira.multas.simularPagamento(emprestimo.id(), "123456"));
        terceira.devolucoes.inspecionar(id, false);

        Biblioteca quarta = abrir(url);
        assertFalse(quarta.multas.simularPagamento(emprestimo.id(), "123456"));
        assertEquals(SituacaoCobranca.QUITADA, quarta.multas.consultar(emprestimo.id()).cobranca().situacao());
        assertFalse(quarta.devolucoes.pendentes().contains(id));
        quarta.livros.excluir(id);
        assertTrue(quarta.organizacao.listar(id).isEmpty());
        quarta.livros.incluir(new Livro(id, "Novo exemplar"));
        assertNull(quarta.emprestimos.consultarSituacao(id).ultimoEmprestimo());
        assertNull(quarta.multas.consultarPorLivro(id));
        assertNotNull(quarta.multas.consultar(emprestimo.id())); // histórico não é apagado
        quarta.livros.excluir(id);
        quarta.persistencia.unidade().executar(() -> {
            if (anterior == null) quarta.persistencia.movimentacoes().remover("organizacao:arvore");
            else quarta.persistencia.movimentacoes().salvar("organizacao:arvore", anterior);
            return null;
        });
    }
}
