package biblioteca;
import org.junit.Test;
import static org.junit.Assert.*;
import java.time.*;
import java.io.*;
import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.*;
import java.util.concurrent.*;

public class AlinhamentoTest {
    private Biblioteca criar(FabricaPersistencia f) {
        return new Biblioteca(f,new PoliticaOriginal(),new AdaptadorBancoA(new BancoA(f.unidade(),f.movimentacoes())), x->{},false,false);
    }
    private Biblioteca preparar(FabricaPersistencia f) {
        Biblioteca b=criar(f);
        b.pessoas.incluir(new Pessoa("12345678909","Ana","ana@example.org"));
        b.estudantes.incluir(new Estudante("E1","12345678909"));
        b.livros.incluir(new Livro("L1","Original"));
        return b;
    }
    private void emprestar(Biblioteca b) {
        b.emprestimos.solicitar(new SolicitarReserva(UUID.randomUUID(),"E1",Instant.now(),"L1"));
        b.emprestimos.emprestar("L1","E1",LocalDate.of(2026,11,10));
    }
    @Test public void excluirLivroDeveRemoverReferenciaDaArvore() {
        Biblioteca b=preparar(new FabricaEmMemoria());
        b.organizacao.criarGrupo("raiz","Secao");
        b.organizacao.incluirLivro("Secao","L1");
        b.livros.excluir("L1");
        assertNull(b.livros.consultar("L1"));
        assertEquals("Livro excluido permanece contado na arvore",0,b.organizacao.contar("Secao"));
    }
    @Test public void recadastroNaoDeveHerdarUltimoEmprestimoDoLivroExcluido() {
        Biblioteca b=preparar(new FabricaEmMemoria()); emprestar(b);
        b.devolucoes.devolver("L1","E1",LocalDate.of(2026,11,15));
        b.devolucoes.inspecionar("L1",false);
        b.livros.excluir("L1"); b.livros.incluir(new Livro("L1","Outro livro"));
        assertNull("Novo cadastro herdou vinculo do livro excluido",b.emprestimos.consultarSituacao("L1").ultimoEmprestimo());
    }
    @Test public void recadastroNaoDeveConsultarMultaDoLivroExcluido() {
        Biblioteca b=preparar(new FabricaEmMemoria()); emprestar(b);
        b.devolucoes.devolver("L1","E1",LocalDate.of(2026,11,15));
        b.devolucoes.inspecionar("L1",false);
        b.livros.excluir("L1"); b.livros.incluir(new Livro("L1","Outro livro"));
        assertNull("Consulta do novo cadastro retorna multa antiga",b.multas.consultarPorLivro("L1"));
    }
    @Test public void reconstruirAplicacaoComMesmaPersistenciaDeveManterGrupos() {
        FabricaPersistencia f=new FabricaEmMemoria(); Biblioteca b=preparar(f);
        b.organizacao.criarGrupo("raiz","Secao"); b.organizacao.incluirLivro("Secao","L1");
        Biblioteca reaberta=criar(f);
        assertNotNull(reaberta.livros.consultar("L1"));
        assertEquals("Grupo perdido ao reconstruir a aplicacao",1,reaberta.organizacao.contar("Secao"));
    }
    @Test public void reconstruirAplicacaoDeveEncontrarInspecoesPendentes() {
        FabricaPersistencia f=new FabricaEmMemoria(); Biblioteca b=preparar(f);emprestar(b);
        b.devolucoes.devolver("L1","E1",LocalDate.of(2026,11,10));
        Biblioteca reaberta=criar(f);
        assertEquals("DEVOLVIDO",reaberta.emprestimos.consultarSituacao("L1").estado());
        assertTrue("Livro devolvido desaparece da fila",reaberta.devolucoes.pendentes().contains("L1"));
    }
    @Test public void publicacaoAtrasadaNaoDeveReincluirLivroJaInspecionado() throws Exception {
        Biblioteca b=preparar(new FabricaEmMemoria());emprestar(b);
        CountDownLatch comitou=new CountDownLatch(1),liberar=new CountDownLatch(1);
        PublicadorDevolucao pub=new PublicadorDevolucao();
        pub.registrar(ev->{comitou.countDown();try{if(!liberar.await(5,TimeUnit.SECONDS))throw new IllegalStateException("timeout");}catch(InterruptedException e){throw new IllegalStateException(e);}});
        pub.registrar(b.inspecao);
        FachadaCirculacao fachada=new FachadaCirculacao(b.persistencia.unidade(),b.acervo,b.movimentacao,b.relacionamento,pub);
        ExecutorService pool=Executors.newSingleThreadExecutor();
        try {
            Future<?> fut=pool.submit(()->fachada.devolver("L1","E1",LocalDate.of(2026,11,10)));
            assertTrue(comitou.await(5,TimeUnit.SECONDS));
            b.devolucoes.inspecionar("L1",false);
            liberar.countDown();fut.get(5,TimeUnit.SECONDS);
            assertEquals("LIVRE",b.emprestimos.consultarSituacao("L1").estado());
            assertFalse("Evento atrasado recriou pendencia de livro LIVRE",b.devolucoes.pendentes().contains("L1"));
        } finally {liberar.countDown();pool.shutdownNow();}
    }

    @Test public void estadosValidamReservaEAutoriaAntesDeMudarLivro() {
        var ana = new Estudante("E1", "12345678909");
        var bia = new Estudante("E2", "98765432100");
        Livro livre = new Livro("L1", "Java");
        assertThrows(IllegalArgumentException.class, () -> livre.reservar(null));
        ResultadoReserva reservado = livre.reservar(ana);
        assertEquals("LIVRE", livre.estado().codigo());
        assertThrows(IllegalStateException.class, () -> reservado.livro().emprestar(reservado.reserva(), bia, LocalDate.of(2026,12,10)));
        assertThrows(IllegalStateException.class, () -> reservado.livro().emprestar(
                new Reserva(UUID.randomUUID(), "OUTRO", ana.id()), ana, LocalDate.of(2026,12,10)));
        var emprestado = reservado.livro().emprestar(reservado.reserva(), ana, LocalDate.of(2026,12,10));
        assertThrows(IllegalStateException.class, () -> emprestado.livro().renovar(emprestado.emprestimo(), bia.id()));
        assertThrows(IllegalStateException.class, () -> emprestado.livro().devolver(emprestado.emprestimo(), bia,
                new Pessoa(bia.cpf(), "Bia", "bia@example.org"), LocalDate.of(2026,12,11)));
        assertEquals(0, emprestado.emprestimo().renovacoes());
        assertEquals("EMPRESTADO", emprestado.livro().estado().codigo());
    }

    @Test public void novaPoliticaApareceNoMenuSemMudarServico() throws Exception {
        Biblioteca b=preparar(new FabricaEmMemoria());
        emprestar(b);
        var antigo=b.emprestimos.consultarSituacao("L1").ultimoEmprestimo();
        b.devolucoes.devolver("L1","E1",LocalDate.of(2026,11,15));
        PoliticaMulta nova=new PoliticaMulta() {
            public String nome() {return "dobro";}
            public BigDecimal calcular(long dias) {return reais(dias).multiply(new BigDecimal("2"));}
        };
        b.configuracaoMulta.registrar("dobro",nova);
        var saida=new ByteArrayOutputStream();
        new Main(b,new Scanner("5\n2\n4\n0\n0\n"),new PrintStream(saida,true,"UTF-8")).executarMenu();
        assertTrue(saida.toString("UTF-8").contains("4 — dobro"));
        assertEquals("dobro",b.multas.politicaVigente());
        b.devolucoes.inspecionar("L1",false);emprestar(b);
        b.devolucoes.devolver("L1","E1",LocalDate.of(2026,11,15));
        assertEquals(new BigDecimal("10.00"),b.multas.consultarPorLivro("L1").valor());
        assertEquals(new BigDecimal("5.00"),b.multas.consultar(antigo).valor());
    }

    @Test public void falhaNaExclusaoDesfazLivroGrupoEIndice() {
        FabricaEmMemoria real=new FabricaEmMemoria();
        AtomicBoolean falhar=new AtomicBoolean();
        Repositorio<Serializable> movimentos=new Repositorio<>() {
            public Serializable buscar(String id) {return real.movimentacoes().buscar(id);}
            public void salvar(String id,Serializable valor) {real.movimentacoes().salvar(id,valor);}
            public void remover(String id) {
                if(falhar.get() && id.equals("ultimo:L1"))throw new IllegalStateException("Falha simulada");
                real.movimentacoes().remover(id);
            }
        };
        FabricaPersistencia f=new FabricaPersistencia() {
            public UnidadeDeTrabalho unidade(){return real.unidade();}
            public Repositorio<RegistroLivro> livros(){return real.livros();}
            public Repositorio<Serializable> movimentacoes(){return movimentos;}
            public Repositorio<Serializable> relacionamentos(){return real.relacionamentos();}
        };
        Biblioteca b=preparar(f);emprestar(b);
        b.devolucoes.devolver("L1","E1",LocalDate.of(2026,11,15));b.devolucoes.inspecionar("L1",false);
        b.organizacao.criarGrupo("raiz","A");b.organizacao.incluirLivro("A","L1");
        var antes=b.emprestimos.consultarSituacao("L1");
        falhar.set(true);
        assertThrows(IllegalStateException.class,()->b.livros.excluir("L1"));
        assertEquals(antes,b.emprestimos.consultarSituacao("L1"));
        assertEquals(List.of("L1"),b.organizacao.listar("A"));
        assertNotNull(b.multas.consultarPorLivro("L1"));
    }

    @Test public void instanciasCompartilhamGruposSemPerderMudancas() {
        FabricaEmMemoria f=new FabricaEmMemoria();Biblioteca primeira=preparar(f),segunda=criar(f);
        primeira.organizacao.criarGrupo("raiz","A");segunda.organizacao.criarGrupo("raiz","B");
        primeira.organizacao.incluirLivro("A","L1");
        assertThrows(IllegalArgumentException.class,()->segunda.organizacao.incluirLivro("B","L1"));
        segunda.organizacao.mover("A","B","L1");
        assertTrue(primeira.organizacao.listar("A").isEmpty());
        assertEquals(List.of("L1"),primeira.organizacao.listar("B"));
    }

    @Test public void serializacaoSuportaHierarquiaComMaisDeDozeNiveis() throws Exception {
        FabricaEmMemoria f=new FabricaEmMemoria();Biblioteca b=preparar(f);
        String pai="raiz";
        for(int i=0;i<20;i++){String id="G"+i;b.organizacao.criarGrupo(pai,id);pai=id;}
        b.organizacao.incluirLivro(pai,"L1");
        Serializable registro=f.unidade().executar(()->f.movimentacoes().buscar("organizacao:arvore"));
        var bytes=new ByteArrayOutputStream();
        try(var out=new ObjectOutputStream(bytes)){out.writeObject(registro);}
        try(var in=new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            in.setObjectInputFilter(ObjectInputFilter.Config.createFilter("maxdepth=12;maxbytes=1048576;biblioteca.*;java.base/*;!*"));
            Serializable restaurado=(Serializable)in.readObject();
            f.unidade().executar(()->{f.movimentacoes().salvar("organizacao:arvore",restaurado);return null;});
        }
        Biblioteca reaberta=criar(f);
        assertEquals(1,reaberta.organizacao.contar("raiz"));
        assertTrue(reaberta.organizacao.hierarquia().contains(new EntradaAcervo("L1",21,false,1)));
    }
}
