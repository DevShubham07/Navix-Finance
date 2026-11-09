# One-off runner for the simulation SQL, run as an ECS task inside the private network.
FROM postgres:18-alpine
COPY sim-run.sh 10_prepare.sql 20_training_loans.sql 30_verify.sql /sim/
# A Windows checkout can leave CRLF line endings, which break the shebang in Linux.
RUN sed -i 's/\r$//' /sim/sim-run.sh && chmod +x /sim/sim-run.sh
ENTRYPOINT ["/sim/sim-run.sh"]
