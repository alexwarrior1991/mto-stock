# Domain Model

Entities:
- Material
- Assembly
- AssemblyComponent (BOM)
- Warehouse
- StockMovement
- Reservation
- Supplier
- Project

Stock is never stored.

Stock = Entries - Outputs + PositiveAdjustments - NegativeAdjustments + IncomingTransfers - OutgoingTransfers

Assemblies have no stock. Availability is calculated from the BOM and current component stock.

Support multiple warehouses and reservations.
