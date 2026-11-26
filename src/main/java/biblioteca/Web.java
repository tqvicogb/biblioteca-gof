package biblioteca;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class Web {
    private final Biblioteca biblioteca;

    private Web(Biblioteca biblioteca) { this.biblioteca = biblioteca; }

    public static void main(String[] args) throws IOException {
        var config = System.getenv();
        int porta = Integer.parseInt(config.getOrDefault("BIB_PORTA", "8080"));
        var web = new Web(Biblioteca.configurar(config));
        var servidor = HttpServer.create(new InetSocketAddress("127.0.0.1", porta), 0);
        servidor.createContext("/", web::atender);
        servidor.start();
        System.out.println("Biblioteca em http://127.0.0.1:" + servidor.getAddress().getPort());
    }

    private void atender(HttpExchange troca) throws IOException {
        try (troca) {
            String caminho = troca.getRequestURI().getPath();
            String metodo = troca.getRequestMethod();
            if (metodo.equals("GET")) {
                switch (caminho) {
                    case "/" -> recurso(troca, "index.html", "text/html");
                    case "/app.js" -> recurso(troca, "app.js", "text/javascript");
                    case "/style.css" -> recurso(troca, "style.css", "text/css");
                    default -> enviar(troca, 404, "text/plain", "Página não encontrada");
                }
                return;
            }
            if (!metodo.equals("POST") || !caminho.equals("/operacao")) {
                enviar(troca, 404, "text/plain", "Rota não encontrada");
                return;
            }
            // A aplicação é local. O cabeçalho força preflight para chamadas vindas de outras origens.
            if (!"1".equals(troca.getRequestHeaders().getFirst("X-Biblioteca"))) {
                enviar(troca, 403, "text/plain", "Use o formulário da biblioteca");
                return;
            }
            byte[] corpo = troca.getRequestBody().readNBytes(8193);
            if (corpo.length > 8192) {
                enviar(troca, 413, "text/plain", "Formulário excede o limite");
                return;
            }
            try {
                enviar(troca, 200, "text/plain", executar(campos(new String(corpo, StandardCharsets.UTF_8))));
            } catch (IllegalStateException erro) {
                if (erro.getCause() instanceof java.sql.SQLException) {
                    enviar(troca, 503, "text/plain", "MySQL indisponível. Confira a conexão e o esquema.");
                } else enviar(troca, 409, "text/plain", erro.getMessage());
            } catch (IllegalArgumentException | java.time.DateTimeException erro) {
                enviar(troca, 400, "text/plain", erro.getMessage());
            }
        }
    }

    private String executar(Map<String, String> p) {
        String operacao = campo(p, "acao");
        return switch (operacao) {
            case "livro" -> cadastro(biblioteca.livros.incluir(new Livro(campo(p,"livro"), campo(p,"titulo"))));
            case "pessoa" -> cadastro(biblioteca.pessoas.incluir(new Pessoa(campo(p,"cpf"), campo(p,"nome"), campo(p,"email"))));
            case "estudante" -> cadastro(biblioteca.estudantes.incluir(new Estudante(campo(p,"estudante"), campo(p,"cpf"))));
            case "atendente" -> cadastro(biblioteca.atendentes.incluir(new Atendente(campo(p,"atendente"), campo(p,"cpf"))));
            case "consulta" -> {
                var livro = biblioteca.livros.consultar(campo(p,"livro"));
                if (livro == null) throw new IllegalArgumentException("Livro não encontrado");
                yield livro.titulo() + " • " + livro.id();
            }
            case "reserva-atual" -> {
                var reserva = biblioteca.emprestimos.consultarReserva(campo(p,"livro"), campo(p,"estudante"));
                yield reserva == null ? "Sem reserva para este estudante" : "Reserva ativa • " + reserva.id();
            }
            case "reservar" -> comprovante(biblioteca.emprestimos.solicitar(new SolicitarReserva(
                    UUID.fromString(campo(p,"uuid")), campo(p,"estudante"), Instant.now(), campo(p,"livro"))));
            case "renovar" -> comprovante(biblioteca.emprestimos.solicitar(new SolicitarRenovacao(
                    UUID.fromString(campo(p,"uuid")), campo(p,"estudante"), Instant.now(), campo(p,"livro"))));
            case "emprestar" -> comprovante(biblioteca.emprestimos.emprestar(
                    campo(p,"livro"), campo(p,"estudante"), LocalDate.parse(campo(p,"data"))));
            case "devolver" -> comprovante(biblioteca.devolucoes.devolver(
                    campo(p,"livro"), campo(p,"estudante"), LocalDate.parse(campo(p,"data"))));
            case "inspecionar" -> comprovante(biblioteca.devolucoes.inspecionar(campo(p,"livro"), switch (campo(p,"laudo")) {
                case "bom" -> false;
                case "danificado" -> true;
                default -> throw new IllegalArgumentException("Resultado de inspeção inválido");
            }));
            case "reparar" -> comprovante(biblioteca.devolucoes.enviarParaReparo(campo(p,"livro")));
            case "retornar" -> comprovante(biblioteca.devolucoes.retornarDoReparo(campo(p,"livro")));
            case "laudo" -> comprovante(biblioteca.devolucoes.laudoIrrecuperavel(campo(p,"livro")));
            case "multa" -> {
                var boleto = biblioteca.multas.consultar(UUID.fromString(campo(p,"uuid")));
                yield boleto == null ? "Nenhum boleto emitido para o empréstimo"
                        : "R$ " + boleto.valor().toPlainString().replace('.', ',') + "\nPolítica: " + boleto.politica()
                        + "\nSituação: " + boleto.cobranca().situacao() + "\nReferência: " + boleto.cobranca().referencia();
            }
            case "grupo" -> {
                biblioteca.organizacao.criarGrupo(campo(p,"origem"), campo(p,"destino"));
                yield "Grupo criado";
            }
            case "organizar" -> {
                biblioteca.organizacao.incluirLivro(campo(p,"origem"), campo(p,"livro"));
                yield "Livro incluído no grupo";
            }
            case "mover" -> {
                biblioteca.organizacao.mover(campo(p,"origem"), campo(p,"destino"), campo(p,"livro"));
                yield "Livro movido";
            }
            case "listar" -> {
                var itens = biblioteca.organizacao.listar(campo(p,"origem"));
                yield "Total: " + itens.size() + "\n" + String.join("\n", itens);
            }
            default -> throw new IllegalArgumentException("Operação desconhecida");
        };
    }

    private String cadastro(ResultadoCadastro<?> resultado) {
        return resultado.existente() ? "Cadastro existente" : "Cadastro concluído";
    }

    private String comprovante(Comprovante resultado) {
        String vencimento = resultado.vencimento() == null ? "" : "\nVencimento: " + resultado.vencimento();
        return resultado.operacao() + " • " + resultado.estado() + "\nComprovante: " + resultado.id() + vencimento;
    }

    private static Map<String, String> campos(String corpo) {
        Map<String, String> campos = new HashMap<>();
        for (String item : corpo.split("&")) {
            String[] par = item.split("=", 2);
            if (par.length == 2) campos.put(URLDecoder.decode(par[0], StandardCharsets.UTF_8),
                    URLDecoder.decode(par[1], StandardCharsets.UTF_8));
        }
        return campos;
    }

    private static String campo(Map<String, String> campos, String chave) {
        String valor = campos.getOrDefault(chave, "").strip();
        if (valor.isEmpty() || valor.length() > 180) throw new IllegalArgumentException("Confira o campo " + chave);
        return valor;
    }

    private static void recurso(HttpExchange troca, String arquivo, String tipo) throws IOException {
        try (var entrada = Web.class.getResourceAsStream("/web/" + arquivo)) {
            if (entrada == null) throw new IOException("Recurso da interface ausente: " + arquivo);
            enviar(troca, 200, tipo, new String(entrada.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    private static void enviar(HttpExchange troca, int status, String tipo, String texto) throws IOException {
        byte[] corpo = texto.getBytes(StandardCharsets.UTF_8);
        var headers = troca.getResponseHeaders();
        headers.set("Content-Type", tipo + "; charset=utf-8");
        headers.set("Cache-Control", "no-store");
        headers.set("X-Content-Type-Options", "nosniff");
        headers.set("Content-Security-Policy", "default-src 'self'; frame-ancestors 'none'; base-uri 'none'");
        troca.sendResponseHeaders(status, corpo.length);
        troca.getResponseBody().write(corpo);
    }
}